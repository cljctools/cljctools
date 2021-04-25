(ns find.app.bittorrent
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.pprint :refer [pprint]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.string]
   [cognitect.transit :as transit]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]))

(defonce fs (js/require "fs-extra"))
(defonce path (js/require "path"))
(defonce Webtorrent (js/require "webtorrent"))
(defonce BittorrentDHT (js/require "bittorrent-dht"))
(defonce fetchMetadata (js/require "bep9-metadata-dl"))
(defonce MagnetURI (js/require "magnet-uri"))
(defonce crypto (js/require "crypto"))
(defonce bencode (js/require "bencode"))
(defonce dgram (js/require "dgram"))

(defn decode-nodes
  [nodesB]
  (for [i (range 0 (.-length nodesB) 26)]
    (let [idB (.slice nodesB i (+ i 20))]
      {:id (.toString idB "hex")
       :idB idB
       :address (str (aget nodesB (+ i 20)) "."
                     (aget nodesB (+ i 21)) "."
                     (aget nodesB (+ i 22)) "."
                     (aget nodesB (+ i 23)))
       :port (.readUInt16BE nodesB (+ i 24))})))

(defn gen-neighbor-id
  [id-targetB id-nodeB]
  (->>
   [(.slice id-targetB 0  10) (.slice id-nodeB 10)]
   (into-array)
   (js/Buffer.concat)))

(defn encode-nodes
  [nodes]
  (->> nodes
       (map (fn [[id node]]
              (->>
               [(:idB node)
                (->>
                 (clojure.string/split (:address node) ".")
                 (map js/parseInt)
                 (into-array)
                 (js/Buffer.from))
                (doto (js/Buffer.alloc 2)
                  (.writeUInt16BE (:port node) 0))]
               (into-array)
               (js/Buffer.concat))))
       (into-array)
       (js/Buffer.concat)))

(defn send-krpc
  [socket msg rinfo]
  (let [msgB (.encode bencode msg)]
    (.send socket msgB 0 (.-length msgB) (. rinfo -port) (. rinfo -address))))

(defn send-find-node
  [socket rifno id-nodeB]
  (let [id-targetB (.randomBytes crypto 20)]
    (send-krpc
     socket
     (clj->js
      {:t (.slice id-targetB 0 4)
       :y "q"
       :q "find_node"
       :a {:id id-nodeB
           :target id-targetB}})
     rifno)))

(defn send-sample-infohashes
  "BEP51"
  [socket rifno id-nodeB]
  (let [id-targetB (.randomBytes crypto 20)]
    (send-krpc
     socket
     (clj->js
      {:t (.slice id-targetB 0 4)
       :y "q"
       :q "sample_infohashes"
       :a {:id id-nodeB
           :target id-targetB}})
     rifno)))

(def transit-write
  (let [handlers {js/Buffer
                  (transit/write-handler
                   (fn [buffer] "js/Buffer")
                   (fn [buffer] (.toString buffer "hex")))
                  cljs.core.async.impl.channels/ManyToManyChannel
                  (transit/write-handler
                   (fn [c|] "ManyToManyChannel")
                   (fn [c|] nil))}
        writer (transit/writer
                :json-verbose
                {:handlers handlers})]
    (fn [data]
      (transit/write writer data))))

(def transit-read
  (let [handlers {"js/Buffer"
                  (fn [string] (js/Buffer.from string "hex"))
                  "ManyToManyChannel"
                  (fn [string] nil)}
        reader (transit/reader
                :json-verbose
                {:handlers handlers})]
    (fn [data]
      (transit/read reader data))))

(defn load-state
  [data-dir]
  (go
    (try
      (let [state-filepath (.join path data-dir "state/" "find.app.bittorrent.edn")]
        (when (.pathExistsSync fs state-filepath)
          (let [data-string (-> (.readFileSync fs state-filepath)
                             (.toString "utf-8"))]
            (transit-read data-string))))
      (catch js/Error error (println ::error-loading-state error)))))

(defn save-state
  [data-dir state]
  (go
    (try
      (let [state-dir (.join path data-dir "state/")
            state-filepath (.join path state-dir "find.app.bittorrent.edn")
            data-string (transit-write state)]
        (.ensureDirSync fs state-dir)
        (.writeFileSync fs state-filepath data-string))
      (catch js/Error error (println ::error-saving-state error)))))

(defn start
  [{:keys [:peer-index
           :data-dir] :as opts}]
  (go
    (let [stateA (atom
                  (merge
                   (let [id-selfB (js/Buffer.from "a8fb5c14469fc7c46e91679c493160ed3d13be3d" "hex") #_(.randomBytes crypto 20)]
                     {:id-self (.toString id-selfB "hex")
                      :id-selfB id-selfB
                      :routing-table {}
                      :routing-table-sampled {}
                      :routing-table-find-noded {}})
                   (<! (load-state data-dir))))
          duration (* 10 60 1000)
          count-torrentsA (atom 0)
          count-messagesA (atom 0)
          routing-table-max-size 16000
          add-node (fn [node]
                     (when (and
                            (< (count (:routing-table @stateA)) routing-table-max-size)
                            (not (get (:routing-table @stateA) (:id node))))
                       (swap! stateA update-in [:routing-table] assoc (:id node) node)))

          nodes-bootstrap [{:address "router.bittorrent.com"
                            :port 6881}
                           {:address "dht.transmissionbt.com"
                            :port 6881}
                           #_{:address "dht.libtorrent.org"
                              :port 25401}]
          port 6881
          address "0.0.0.0"
          torrent| (chan (sliding-buffer 100))
          msg| (chan (sliding-buffer 100))
          infohash| (chan (sliding-buffer 100))
          nodes| (chan (sliding-buffer 100))
          socket (.createSocket dgram "udp4")

          procsA (atom [])
          stop (fn []
                 (doseq [stop| @procsA]
                   (close! stop|))
                 (close! msg|)
                 (close! torrent|)
                 (close! infohash|)
                 (close! nodes|)
                 (.close socket)
                 (a/merge @procsA))]
      (swap! stateA merge {:torrent| torrent|})

      ; save state to file periodically
      (go
        (when-not (.pathExistsSync fs (.join path data-dir "state/" "find.app.bittorrent.edn"))
          (<! (save-state data-dir @stateA)))
        (loop []
          (<! (timeout (* 5 1000)))
          (<! (save-state data-dir @stateA))
          (recur)))

      (println ::id-self (:id-self @stateA))

      (doto socket
        (.bind port address)
        (.on "listening"
             (fn []
               (println (format "listening on %s:%s" address port))))
        (.on "message"
             (fn [msgB rinfo]
               (swap! count-messagesA inc)
               (try
                 (put! msg| {:msg (.decode bencode msgB)
                             :rinfo rinfo})
                 (catch js/Error error (do nil)))))
        (.on "error"
             (fn [error]
               (println ::socket-error)
               (println error))))

      #_(go
          (<! (timeout duration))
          (stop))

      (go
        (loop []
          (<! (timeout (* 10 1000)))
          (println {:count-messages @count-messagesA
                    :count-torrentsA @count-torrentsA
                    :routing-table (count (:routing-table @stateA))
                    :routing-table-find-noded  (count (:routing-table-find-noded-table @stateA))
                    :routing-table-sampledA (count (:routing-table-sampled @stateA))})
          (recur)))

      (go
        (loop []
          (when-let [{:keys [infohashB rinfo]} (<! infohash|)]
            (put! torrent| {:infohash (.toString infohashB "hex")})
            (swap! count-torrentsA inc)
            (recur))))


      (let []
        (add-watch stateA :add-watch
                   (fn [k refA old-state new-state]))
        (go
          (loop [])))

      ; peridiacally remove half nodes randomly 
      (go
        (loop []
          (<! (timeout (* 1 60 1000)))
          (->> (:routing-table @stateA)
               (keys)
               (shuffle)
               (take (/ (count (:routing-table @stateA)) 2))
               (apply swap! stateA update-in [:routing-table] dissoc))
          (recur)))

      ; after time passes, remove nodes from already-asked tables so they can be queried again
      ; this means we politely ask only nodes we haven't asked before
      (go
        (loop []
          (<! (timeout (* 5 1000)))

          (doseq [[id {:keys [node timestamp]}] (:routing-table-sampled @stateA)]
            (when (> (- (js/Date.now) timestamp) (* 5 60 1000))
              (swap! stateA update-in [:routing-table-sampled] dissoc id)))

          (doseq [[id {:keys [node timestamp]}] (:routing-table-find-noded-table @stateA)]
            (when (> (- (js/Date.now) timestamp) (* 5 60 1000))
              (swap! stateA update-in [:routing-table-find-noded] dissoc id)))

          (recur)))

      ; very rarely ask bootstrap servers for nodes
      #_(let [stop| (chan 1)]
          (swap! procsA conj stop|)
          (go
            (loop [timeout| (timeout 0)]
              (alt!
                timeout|
                ([_]
                 (doseq [node nodes-bootstrap]
                   (send-find-node
                    socket
                    (clj->js
                     node)
                    (:id-selfB @stateA)))
                 (recur (timeout (* 3 60 1000))))
                stop|
                (do :stop)))
            (println :proc-find-nodes-bootstrap-exits)))

      ; periodicaly ask nodes for new nodes
      #_(let [stop| (chan 1)]
          (swap! procsA conj stop|)
          (go
            (loop [timeout| (timeout 2000)]
              (alt!
                timeout|
                ([_]
                 (doseq [[id node] (->>
                                    (sequence
                                     (comp
                                      (filter (fn [[id node]] (not (get (:routing-table-find-noded-table @stateA) id))))
                                      (take 64))
                                     (:routing-table @stateA))
                                    (shuffle))]
                   (swap! routing-table-find-nodedA assoc id {:node node
                                                              :timestamp (js/Date.now)})
                   (send-find-node
                    socket
                    (clj->js
                     node)
                    (:id-selfB @stateA)))
                 (recur (timeout (* 2 60 1000))))

                stop|
                (do :stop)))
            (println :proc-find-node-exits)))

      ; sybil
      #_(let [stop| (chan 1)]
          (swap! procsA conj stop|)
          (go
            (loop []
              (alt!
                (timeout 2000)
                ([_]
                 (doseq [[id node] (->>
                                    (sequence
                                     (comp
                                      (filter (fn [[id node]] (not (get (:routing-table-find-noded-table @stateA) id))))
                                      (take 64))
                                     (:routing-table @stateA))
                                    (shuffle))]
                   (swap! routing-table-find-nodedA assoc id {:node node
                                                              :timestamp (js/Date.now)})
                   (send-find-node
                    socket
                    (clj->js
                     node)
                    (gen-neighbor-id (:idB node) (:id-selfB @stateA))))
                 (recur))

                stop|
                (do :stop)))
            (println :proc-sybil-exits)))

      ; ask nodes directly, politely for infohashes
      #_(let [stop| (chan 1)]
          (swap! procsA conj stop|)
          (go
            #_(timeout 1000)
            (loop [timeout| (timeout 1000)]
              (alt!
                timeout|
                ([_]
                 (doseq [[id node] (->>
                                    (sequence
                                     (comp
                                      (filter (fn [[id node]] (not (get (:routing-table-sampled @stateA) id))))
                                      (take 64))
                                     (:routing-table @stateA))
                                    (shuffle))]
                   (swap! routing-table-sampledA assoc id {:node node
                                                           :timestamp (js/Date.now)})
                   (send-sample-infohashes
                    socket
                    (clj->js
                     node)
                    (:id-selfB @stateA)))
                 (recur (timeout (* 10 1000))))

                stop|
                (do :stop)))
            (println :proc-BEP51-exits)))

      ; add new nodes to routing table
      (go
        (loop []
          (when-let [nodesB (<! nodes|)]
            (doseq [node (decode-nodes nodesB)]
              (when (and (not= (:address node) address)
                         (not= 0 (js/Buffer.compare (:idB node) (:id-selfB @stateA)))
                         (< 0 (:port node) 65536))
                (add-node node)))
            #_(println :nodes-count (count (:routing-table @stateA)))
            (recur)))
        (println :proc-add-nodes-exits))

      (go
        (loop []
          (when-let [{:keys [msg rinfo]} (<! msg|)]
            (let [msg-y (some-> (. msg -y) (.toString "utf-8"))
                  msg-q (some-> (. msg -q) (.toString "utf-8"))]

              (cond

                (and (= msg-y "r") (goog.object/getValueByKeys msg "r" "samples"))
                (when (goog.object/getValueByKeys msg "r" "nodes")
                  (doseq [infohashB (->>
                                     (js/Array.from  (goog.object/getValueByKeys msg "r" "samples"))
                                     (partition 20)
                                     (map #(js/Buffer.from (into-array %))))]
                    (put! infohash| {:infohashB infohashB
                                     :rinfo rinfo}))
                  (put! nodes| (.. msg -r -nodes)))

                (and (= msg-y "r") (goog.object/getValueByKeys msg "r" "nodes"))
                (put! nodes| (.. msg -r -nodes))

                (and (= msg-y "q")  (= msg-q "ping"))
                (let [id-targetB  (. msg -t)
                      id-nodeB (.. msg -a -id)]
                  (if (or (not id-targetB) (not= (.-length id-nodeB) 20))
                    (do nil :invalid-data)
                    (send-krpc
                     socket
                     (clj->js
                      {:t id-targetB
                       :y "r"
                       :r {:id (gen-neighbor-id id-nodeB (:id-selfB @stateA))}})
                     rinfo)))

                (and (= msg-y "q")  (= msg-q "find_node"))
                (let [id-targetB  (. msg -t)
                      id-nodeB (.. msg -a -id)]
                  (if (or (not id-targetB) (not= (.-length id-nodeB) 20))
                    (println "invalid query args: find_node")
                    (send-krpc
                     socket
                     (clj->js
                      {:t id-targetB
                       :y "r"
                       :r {:id (gen-neighbor-id id-nodeB (:id-selfB @stateA))
                           :nodes (encode-nodes (take 8 (:routing-table @stateA)))}})
                     rinfo)))

                (and (= msg-y "q")  (= msg-q "get_peers"))
                (let [infohashB  (.. msg -a -info_hash)
                      id-targetB (. msg -t)
                      id-nodeB (.. msg -a -id)
                      tokenB (.slice infohashB 0 2)]
                  (if (or (not id-targetB) (not= (.-length id-nodeB) 20) (not= (.-length infohashB) 20))
                    (println "invalid query args: get_peers")
                    (send-krpc
                     socket
                     (clj->js
                      {:t id-targetB
                       :y "r"
                       :r {:id (gen-neighbor-id infohashB (:id-selfB @stateA))
                           :nodes (encode-nodes (take 8 (:routing-table @stateA)))
                           :token tokenB}})
                     rinfo)))

                (and (= msg-y "q")  (= msg-q "announce_peer"))
                (let [infohashB   (.. msg -a -info_hash)
                      id-targetB (. msg -t)
                      id-nodeB (.. msg -a -id)
                      tokenB (.slice infohashB 0 2)]

                  (cond
                    (not id-targetB)
                    (println "invalid query args: announce_peer")

                    (not= (-> infohashB (.slice 0 2) (.toString)) (.toString tokenB))
                    (println "announce_peer: token and info_hash don't match")

                    :else
                    (do
                      (send-krpc
                       socket
                       (clj->js
                        {:t id-targetB
                         :y "r"
                         :r {:id (gen-neighbor-id infohashB (:id-selfB @stateA))}})
                       rinfo)
                      (put! infohash| {:infohashB infohashB
                                       :rinfo rinfo}))))

                :else
                (do nil)))


            (recur))))

      stateA)))

#_(defn start
    []
    (go
      (let [#_client #_(Webtorrent.
                        (clj->js
                         {"dhtPort" (+ 6880 peer-index)}))
            #_dht #_(. client -dht)
            stateA (atom nil)
            torrent| (chan (sliding-buffer 100))
            dht (BittorrentDHT.
                 (clj->js
                  {"nodeId" "9859552c412933025559388fe1c438422e3afee7"}))]
        (reset! stateA {:dht dht
                        :torrent| torrent|})
        (.listen dht (+ 6880 peer-index)
                 (fn []))
        (.on dht "ready"
             (fn []
               (println ::dht-ready (+ 6880 peer-index))
               #_(println (.. dht (toJSON) -nodes))))
        (.on dht "announce"
             (fn [peer info-hash]
               (println ::announce)
               (println (.-host peer) (.-port peer))
               (println (.toString info-hash "hex"))
               (->
                (fetchMetadata
                 (.toString info-hash "hex")
                 (clj->js
                  {"maxConns" 10
                   "fetchTimeout" 30000
                   "socketTimeout" 1000
                   "dht" dht}))
                (.then (fn [metadata]
                         (println (.. metadata -info -name (toString "utf-8")))
                         (put! torrent| {:name (.. metadata -info -name (toString "utf-8"))})
                         #_(pprint (js->clj metadata))
                         #_(println (.. metadata -info -pieces (toString "hex")))))
                (.catch (fn [error]
                          (println ::error error))))))
        (.on dht "error"
             (fn [error]
               (println ::dht-error)
               (println error)
               (.destroy dht)))
        stateA)))


(comment

  (extend-protocol IPrintWithWriter
    js/Buffer
    (-pr-writer [buffer writer _]
      (write-all writer "#js/buffer \"" (.toString buffer ) "\"")))

  (cljs.reader/register-tag-parser!
   'js/buffer
   (fn [value]
     (js/Buffer.from value )))

  (cljs.reader/read-string
   
   "#js/buffer \"96190f486de62449099f9caf852964b2e12058dd\"")

  (println (cljs.reader/read-string {:readers {'foo identity}} "#foo :asdf"))

  ;
  )
