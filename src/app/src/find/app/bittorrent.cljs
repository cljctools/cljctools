(ns find.app.bittorrent
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.pprint :refer [pprint]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.string]
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

(defn start
  [{:keys [:peer-index] :as opts}]
  (go
    (let [stateA (atom nil)
          id-selfB (.randomBytes crypto 20)
          duration (* 10 60 1000)
          routing-tableA (atom {})
          routing-table-sampledA (atom {})
          routing-table-find-nodedA (atom {})
          count-torrentsA (atom 0)
          count-messagesA (atom 0)
          routing-table-size 512
          add-node (fn [node]
                     (when (and
                            (< (count @routing-tableA) routing-table-size)
                            (not (get @routing-tableA (:id node))))
                       (swap! routing-tableA assoc (:id node) node)))

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

      (go
        (<! (timeout duration))
        (stop))

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

      (go
        (loop []
          (when-let [{:keys [infohashB rinfo]} (<! infohash|)]
            (put! torrent| {:infohash (.toString infohashB "hex")})
            (swap! count-torrentsA inc)
            (recur))))
      (go
        (loop []
          (<! (timeout (* 10 1000)))
          (println {:count-messages @count-messagesA
                    :count-torrentsA @count-torrentsA
                    :routing-table (count @routing-tableA)
                    :routing-table-find-noded  (count @routing-table-find-nodedA)
                    :routing-table-sampledA (count @routing-table-sampledA)})
          (recur)))

      ; peridiacally remove half nodes randomly 
      (go
        (loop []
          (<! (timeout (* 5 60 1000)))
          (->> @routing-tableA
               (keys)
               (shuffle)
               (take (/ routing-table-size 2))
               (apply swap! routing-tableA dissoc))
          (recur)))

      ; after time passes, remove nodes from already-asked tables so they can be queried again
      ; this means we politely ask only nodes we haven't ask before
      (go
        (loop []
          (<! (timeout (* 5 1000)))

          (doseq [[id {:keys [node timestamp]}] @routing-table-sampledA]
            (when (> (- (js/Date.now) timestamp) (* 5 60 1000))
              (swap! routing-table-sampledA dissoc id)))

          (doseq [[id {:keys [node timestamp]}] @routing-table-find-nodedA]
            (when (> (- (js/Date.now) timestamp) (* 2 60 1000))
              (swap! routing-table-find-nodedA dissoc id)))

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
                    id-selfB))
                 (recur (timeout (* 3 * 60 1000))))
                stop|
                (do :stop)))
            (println :proc-find-nodes-bootstrap-exits)))
      (doseq [node nodes-bootstrap]
        (send-find-node
         socket
         (clj->js
          node)
         id-selfB))

      ; sybil attack - backfired
      ; problem: overtime, we ask so many nodes, that program simply cannot handle incomming messages, it can crush router, because our ip:port becomes registered on so many nodes
      ; like 9000 messages a second just by making first query to boostrap servers - simply because we already ran program before and asked nodes, and now they keep sending to our ip:port
      ; wait for them to remove us from DHT
      ; instead: we shuold only ask a few nodes once (a day), but from different parts of DHT - and listen to those
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
                                      (filter (fn [[id node]] (not (get @routing-table-find-nodedA id))))
                                      (take 64))
                                     @routing-tableA)
                                    (shuffle))]
                   (swap! routing-table-find-nodedA assoc id {:node node
                                                              :timestamp (js/Date.now)})
                   (send-find-node
                    socket
                    (clj->js
                     node)
                    (gen-neighbor-id (:idB node) id-selfB)))
                 (recur))

                stop|
                (do :stop)))
            (println :proc-find-node-exits)))

      ; ask nodes directly, politely for infohashes
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          #_(timeout 1000)
          (loop []
            (alt!
              (timeout 5000)
              ([_]
               (doseq [[id node] (->>
                                  (sequence
                                   (comp
                                    (filter (fn [[id node]] (not (get @routing-table-sampledA id))))
                                    (take 64))
                                   @routing-tableA)
                                  (shuffle))]
                 (swap! routing-table-sampledA assoc id {:node node
                                                         :timestamp (js/Date.now)})
                 (send-sample-infohashes
                  socket
                  (clj->js
                   node)
                  id-selfB))
               (recur))

              stop|
              (do :stop)))
          (println :proc-BEP51-exits)))

      ; add new nodes to routing table
      (go
        (loop []
          (when-let [nodesB (<! nodes|)]
            (doseq [node (decode-nodes nodesB)]
              (when (and (not= (:address node) address)
                         (not= 0 (js/Buffer.compare (:idB node) id-selfB))
                         (< 0 (:port node) 65536))
                (add-node node)))
            #_(println :nodes-count (count @routing-tableA))
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
                       :r {:id (gen-neighbor-id id-nodeB id-selfB)}})
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
                       :r {:id (gen-neighbor-id id-nodeB id-selfB)
                           :nodes (encode-nodes (take 8 @routing-tableA))}})
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
                       :r {:id (gen-neighbor-id infohashB id-selfB)
                           :nodes (encode-nodes (take 8 @routing-tableA))
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
                         :r {:id (gen-neighbor-id infohashB id-selfB)}})
                       rinfo)
                      (put! infohash| {:infohashB infohashB
                                       :rinfo rinfo}))))

                :else
                (do nil)))


            (recur))))
      (reset! stateA {:torrent| torrent|})
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
