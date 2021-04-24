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
    {:idB (.slice nodesB i (+ i 20))
     :address (str (aget nodesB (+ i 20)) "."
                   (aget nodesB (+ i 21)) "."
                   (aget nodesB (+ i 22)) "."
                   (aget nodesB (+ i 23)))
     :port (.readUInt16BE nodesB (+ i 24))}))

(defn gen-neighbor-id
  [idB-target idB-node]
  (->>
   [(.slice idB-target 0  10) (.slice idB-node 10)]
   (into-array)
   (js/Buffer.concat)))

(defn encode-nodes
  [nodes]
  (->> nodes
       (map (fn [node]
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
  [socket rifno idB-node]
  (let [idB-target (.randomBytes crypto 20)]
    (send-krpc
     socket
     (clj->js
      {:t (.slice idB-target 0 4)
       :y "q"
       :q "find_node"
       :a {:id idB-node
           :target idB-target}})
     rifno)))

(defn send-sample-infohashes
  "BEP51"
  [socket rifno idB-node]
  (let [idB-target (.randomBytes crypto 20)]
    (send-krpc
     socket
     (clj->js
      {:t (.slice idB-target 0 4)
       :y "q"
       :q "sample_infohashes"
       :a {:id idB-node
           :target idB-target}})
     rifno)))

(defn start
  [{:keys [:peer-index] :as opts}]
  (go
    (let [stateA (atom nil)
          idB-self (.randomBytes crypto 20)
          duration (* 2 60 1000)
          nodesA (atom [])

          add-node (fn [node]
                     (when (< (count @nodesA) 256)
                       (swap! nodesA conj node)))

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
               (println ::socket-listening)))
        (.on "message"
             (fn [msgB rinfo] (put! msg| {:msg (.decode bencode msgB)
                                          :rinfo rinfo})))
        (.on "error"
             (fn [error]
               (println ::socket-error)
               (println error))))

      (go
        (loop []
          (when-let [{:keys [infohashB rinfo]} (<! infohash|)]
            (put! torrent| {:infohash (.toString infohashB "hex")})
            (recur))))

      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop []
            (alt!
              (timeout 2000)
              ([_]
               (doseq [node nodes-bootstrap]
                 (send-find-node
                  socket
                  (clj->js
                   node)
                  idB-self))
               (recur))

              stop|
              (do :stop)))
          (println :proc-nodes-bootstrap-exits)))


      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop []
            (alt!
              (timeout 2500)
              ([_]
               (doseq [node @nodesA]
                 (send-find-node
                  socket
                  (clj->js
                   node)
                  (gen-neighbor-id (:idB node) idB-self)))
               (recur))

              stop|
              (do :stop)))
          (println :proc-sybil-exits)))

      #_(let [stop| (chan 1)]
          (swap! procsA conj stop|)
          (go
            (loop []
              (alt!
                (timeout 2500)
                ([_]
                 (doseq [node @nodesA]
                   (send-sample-infohashes
                    socket
                    (clj->js
                     node)
                    idB-self))
                 (recur))

                stop|
                (do :stop)))
            (println :proc-BEP51-exits)))

      (go
        (loop []
          (when-let [nodesB (<! nodes|)]
            (doseq [node (decode-nodes nodesB)]
              (when (and (not= (:address node) address)
                         (not= 0 (js/Buffer.compare (:idB node) idB-self))
                         (< 0 (:port node) 65536))
                (add-node node)))
            #_(println :nodes-count (count @nodesA))
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
                    (println :infohash (.toString infohashB "hex"))
                    (put! infohash| {:infohashB infohashB
                                     :rinfo rinfo}))
                  (put! nodes| (.. msg -r -nodes)))

                (and (= msg-y "r") (goog.object/getValueByKeys msg "r" "nodes"))
                (put! nodes| (.. msg -r -nodes))

                (and (= msg-y "q")  (= msg-q "ping"))
                (let [idB-target  (. msg -t)
                      idB-node (.. msg -a -id)]
                  (if (or (not idB-target) (not= (.-length idB-node) 20))
                    (do nil :invalid-data)
                    (send-krpc
                     socket
                     (clj->js
                      {:t idB-target
                       :y "r"
                       :r {:id (gen-neighbor-id idB-node idB-self)}})
                     rinfo)))

                (and (= msg-y "q")  (= msg-q "find_node"))
                (let [idB-target  (. msg -t)
                      idB-node (.. msg -a -id)]
                  (if (or (not idB-target) (not= (.-length idB-node) 20))
                    (println "invalid query args: find_node")
                    (send-krpc
                     socket
                     (clj->js
                      {:t idB-target
                       :y "r"
                       :r {:id (gen-neighbor-id idB-node idB-self)
                           :nodes (encode-nodes (take 8 @nodesA))}})
                     rinfo)))

                (and (= msg-y "q")  (= msg-q "get_peers"))
                (let [infohashB  (.. msg -a -info_hash)
                      idB-target (. msg -t)
                      idB-node (.. msg -a -id)
                      tokenB (.slice infohashB 0 2)]
                  (if (or (not idB-target) (not= (.-length idB-node) 20) (not= (.-length infohashB) 20))
                    (println "invalid query args: get_peers")
                    (send-krpc
                     socket
                     (clj->js
                      {:t idB-target
                       :y "r"
                       :r {:id (gen-neighbor-id infohashB idB-self)
                           :nodes (encode-nodes (take 8 @nodesA))
                           :token tokenB}})
                     rinfo)))

                (and (= msg-y "q")  (= msg-q "announce_peer"))
                (let [infohashB   (.. msg -a -info_hash)
                      idB-target (. msg -t)
                      idB-node (.. msg -a -id)
                      tokenB (.slice infohashB 0 2)]

                  (cond
                    (not idB-target)
                    (println "invalid query args: announce_peer")

                    (not= (-> infohashB (.slice 0 2) (.toString)) (.toString tokenB))
                    (println "announce_peer: token and info_hash don't match")

                    :else
                    (do
                      (send-krpc
                       socket
                       (clj->js
                        {:t idB-target
                         :y "r"
                         :r {:id (gen-neighbor-id infohashB idB-self)}})
                       rinfo)
                      (println :infohash (.toString infohashB "hex"))
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
