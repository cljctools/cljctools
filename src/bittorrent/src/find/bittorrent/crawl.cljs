(ns find.bittorrent.crawl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]

   [tick.alpha.api :as t]
   [find.bittorrent.core :refer [hash-key-distance-comparator-fn
                                 send-krpc-request-fn
                                 send-krpc
                                 encode-nodes
                                 decode-nodes
                                 sorted-map-buffer]]
   [find.bittorrent.state-file :refer [save-state load-state]]
   [find.bittorrent.dht]
   [find.bittorrent.find-nodes]
   [find.bittorrent.sybil]
   [find.bittorrent.metadata]
   [find.bittorrent.sample-infohashes]))

(defonce fs (js/require "fs-extra"))
(defonce path (js/require "path"))
(defonce bencode (js/require "bencode"))
(defonce dgram (js/require "dgram"))

(defn start
  [{:as opts
    :keys [peer-index
           data-dir]}]
  (go
    (let [stateA (atom
                  (merge
                   (let [self-idB (js/Buffer.from "a8fb5c14469fc7c46e91679c493160ed3d13be3d" "hex") #_(.randomBytes crypto 20)]
                     {:self-id (.toString self-idB "hex")
                      :self-idB self-idB
                      :routing-table (sorted-map)
                      :dht-keyspace {}
                      :routing-table-sampled {}
                      :routing-table-find-noded {}})
                   (<! (load-state data-dir))))

          self-id (:self-id @stateA)
          self-idB (:self-idB @stateA)

          port 6881
          address "0.0.0.0"
          socket (.createSocket dgram "udp4")

          msg| (chan (sliding-buffer 100))
          msg|mult (mult msg|)
          torrent| (chan (sliding-buffer 100))
          torrent|mult (mult torrent|)

          unique-infohashsesA (atom #{})
          xf-infohash (comp
                       (map (fn [{:keys [infohashB] :as value}]
                              (assoc value :infohash (.toString infohashB "hex"))))
                       (filter (fn [{:keys [infohash]}]
                                 (not (get @unique-infohashsesA infohash))))
                       (map (fn [{:keys [infohash] :as value}]
                              (swap! unique-infohashsesA conj infohash)
                              value)))

          infohashes-from-sampling| (chan (sliding-buffer 100000) xf-infohash)
          infohashes-from-listening| (chan (sliding-buffer 100000) xf-infohash)
          infohashes-from-sybil| (chan (sliding-buffer 100000) xf-infohash)

          infohashes-from-sampling|mult (mult infohashes-from-sampling|)
          infohashes-from-listening|mult (mult infohashes-from-listening|)
          infohashes-from-sybil|mult (mult infohashes-from-sybil|)

          nodesB| (chan (sliding-buffer 100))

          send-krpc-request (send-krpc-request-fn {:msg|mult msg|mult})

          valid-node? (fn [node]
                        (and (not= (:address node) address)
                             (not= (:id node) self-id)
                             #_(not= 0 (js/Buffer.compare (:id node) self-id))
                             (< 0 (:port node) 65536)))

          routing-table-nodes| (chan (sliding-buffer 1024)
                                     (map (fn [nodes] (filter valid-node? nodes))))

          dht-keyspace-nodes| (chan (sliding-buffer 1024)
                                    (map (fn [nodes] (filter valid-node? nodes))))


          _ (find.bittorrent.dht/start-routing-table {:stateA stateA
                                                      :self-idB self-idB
                                                      :nodes| routing-table-nodes|
                                                      :send-krpc-request send-krpc-request
                                                      :socket socket
                                                      :routing-table-max-size 128})


          _ (find.bittorrent.dht/start-dht-keyspace {:stateA stateA
                                                     :self-idB self-idB
                                                     :nodes| dht-keyspace-nodes|
                                                     :send-krpc-request send-krpc-request
                                                     :socket socket
                                                     :routing-table-max-size 128})
          xf-node-for-sampling? (comp
                                 (filter valid-node?)
                                 (filter (fn [node] (not (get (:routing-table-sampled @stateA) (:id node)))))
                                 (map (fn [node] [(:id node) node])))

          nodes-to-sample| (chan (sorted-map-buffer 10000 (hash-key-distance-comparator-fn  self-idB))
                                 xf-node-for-sampling?)

          nodes-from-sampling| (chan (sorted-map-buffer 10000 (hash-key-distance-comparator-fn  self-idB))
                                     xf-node-for-sampling?)

          _ (<! (onto-chan! nodes-to-sample|
                            (->> (:routing-table @stateA)
                                 (shuffle)
                                 (take 8)
                                 (map second))
                            false))

          duration (* 10 60 1000)
          nodes-bootstrap [{:address "router.bittorrent.com"
                            :port 6881}
                           {:address "dht.transmissionbt.com"
                            :port 6881}
                           #_{:address "dht.libtorrent.org"
                              :port 25401}]

          count-torrentsA (atom 0)
          count-infohashes-from-samplingA (atom 0)
          count-infohashes-from-listeningA (atom 0)
          count-infohashes-from-sybilA (atom 0)
          count-discoveryA (atom 0)
          count-discovery-activeA (atom 0)
          count-messagesA (atom 0)
          count-messages-sybilA (atom 0)
          started-at (js/Date.now)

          sybils| (chan 30000)

          procsA (atom [])
          stop (fn []
                 (doseq [stop| @procsA]
                   (close! stop|))
                 (close! msg|)
                 (close! torrent|)
                 (close! infohashes-from-sampling|)
                 (close! infohashes-from-listening|)
                 (close! infohashes-from-sybil|)
                 (close! nodes-to-sample|)
                 (close! nodesB|)
                 (.close socket)
                 (a/merge @procsA))]

      (println ::self-id (:self-id @stateA))

      (swap! stateA merge {:torrent| (let [out| (chan (sliding-buffer 100))
                                           torrent|tap (tap torrent|mult (chan (sliding-buffer 100)))]
                                       (go
                                         (loop []
                                           (when-let [value (<! torrent|tap)]
                                             (offer! out| value)
                                             (recur))))
                                       out|)})

      #_(go
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

      ; save state to file periodically
      (go
        (when-not (.pathExistsSync fs (.join path data-dir "state/" "find.app.bittorrent.edn"))
          (<! (save-state data-dir @stateA)))
        (loop []
          (<! (timeout (* 4.5 1000)))
          (<! (save-state data-dir @stateA))
          (recur)))


      ; print info
      (let [stop| (chan 1)
            filepath (.join path data-dir "state/" "find.bittorrent.crawl-log.edn")
            _ (.removeSync fs filepath)
            _ (.ensureFileSync fs filepath)
            write-stream (.createWriteStream fs filepath #js {:flags "a"})
            release (fn []
                      (.end write-stream))]
        (swap! procsA conj stop|)
        (go
          (loop []
            (alt!

              (timeout (* 5 1000))
              ([_]
               (let [state @stateA
                     info [[:infohashes [:total (+ @count-infohashes-from-samplingA @count-infohashes-from-listeningA @count-infohashes-from-sybilA)
                                         :sampling @count-infohashes-from-samplingA
                                         :listening @count-infohashes-from-listeningA
                                         :sybil @count-infohashes-from-sybilA]]
                           [:discovery [:total @count-discoveryA
                                        :active @count-discovery-activeA]]
                           [:torrents @count-torrentsA]
                           [:nodes-to-sample| (count (.-buf nodes-to-sample|)) :nodes-from-sampling| (count (.-buf nodes-from-sampling|))]
                           [:messages [:dht @count-messagesA :sybil @count-messages-sybilA]]
                           [:sockets @find.bittorrent.metadata/count-socketsA]
                           [:routing-table (count (:routing-table state))]
                           [:dht-keyspace (map (fn [[id routing-table]] (count routing-table)) (:dht-keyspace state))]
                           [:routing-table-find-noded  (count (:routing-table-find-noded state))]
                           [:routing-table-sampled (count (:routing-table-sampled state))]
                           [:sybils| (str (- (.. sybils| -buf -n) (count (.-buf sybils|))) "/" (.. sybils| -buf -n))]
                           [:time (str (int (/ (- (js/Date.now) started-at) 1000 60)) "min")]]]
                 (pprint info)
                 (.write write-stream (with-out-str (pprint info)))
                 (.write write-stream "\n"))
               (recur))

              stop|
              (do :stop)))
          (release)))

      ; count
      (let [infohashes-from-sampling|tap (tap infohashes-from-sampling|mult (chan (sliding-buffer 100000)))
            infohashes-from-listening|tap (tap infohashes-from-listening|mult (chan (sliding-buffer 100000)))
            infohashes-from-sybil|tap (tap infohashes-from-sybil|mult (chan (sliding-buffer 100000)))
            torrent|tap (tap torrent|mult (chan (sliding-buffer 100)))]
        (go
          (loop []
            (let [[value port] (alts! [infohashes-from-sampling|tap
                                       infohashes-from-listening|tap
                                       infohashes-from-sybil|tap
                                       torrent|tap])]
              (when value
                (condp = port
                  infohashes-from-sampling|tap
                  (swap! count-infohashes-from-samplingA inc)

                  infohashes-from-listening|tap
                  (swap! count-infohashes-from-listeningA inc)

                  infohashes-from-sybil|tap
                  (swap! count-infohashes-from-sybilA inc)

                  torrent|tap
                  (swap! count-torrentsA inc))
                (recur))))))

      ; after time passes, remove nodes from already-asked tables so they can be queried again
      ; this means we politely ask only nodes we haven't asked before
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop [timeout| (timeout 0)]
            (alt!
              timeout|
              ([_]
               (doseq [[id {:keys [timestamp]}] (:routing-table-sampled @stateA)]
                 (when (> (- (js/Date.now) timestamp) (* 5 60 1000))
                   (swap! stateA update-in [:routing-table-sampled] dissoc id)))

               (doseq [[id {:keys [timestamp interval]}] (:routing-table-find-noded @stateA)]
                 (when (or
                        (and interval (> (js/Date.now) (+ timestamp (* interval 1000))))
                        (> (- (js/Date.now) timestamp) (* 5 60 1000)))
                   (swap! stateA update-in [:routing-table-find-noded] dissoc id)))
               (recur (timeout (* 10 1000))))

              stop|
              (do :stop)))))

      ; very rarely ask bootstrap servers for nodes
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (find.bittorrent.find-nodes/start-bootstrap-query
         {:stateA stateA
          :self-idB self-idB
          :nodes-bootstrap nodes-bootstrap
          :send-krpc-request send-krpc-request
          :socket socket
          :nodesB| nodesB|
          :stop|  stop|}))

      ; periodicaly ask nodes for new nodes
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (find.bittorrent.find-nodes/start-dht-query
         {:stateA stateA
          :self-idB self-idB
          :send-krpc-request send-krpc-request
          :socket socket
          :nodesB| nodesB|
          :stop| stop|}))

      ; start sybil
      #_(let [stop| (chan 1)]
          (swap! procsA conj stop|)
          (find.bittorrent.sybil/start
           {:stateA stateA
            :nodes-bootstrap nodes-bootstrap
            :sybils| sybils|
            :infohash| infohashes-from-sybil|
            :stop| stop|
            :count-messages-sybilA count-messages-sybilA}))

      ; add new nodes to routing table
      (go
        (loop []
          (when-let [nodesB (<! nodesB|)]
            (let [nodes (decode-nodes nodesB)]
              (>! routing-table-nodes| nodes)
              (>! dht-keyspace-nodes| nodes)
              (<! (onto-chan! nodes-to-sample| nodes false)))
            #_(println :nodes-count (count (:routing-table @stateA)))
            (recur))))

      ; ask peers directly, politely for infohashes
      (find.bittorrent.sample-infohashes/start-sampling
       {:stateA stateA
        :self-idB self-idB
        :send-krpc-request send-krpc-request
        :socket socket
        :infohash| infohashes-from-sampling|
        :nodes-to-sample| nodes-to-sample|
        :nodes-from-sampling| nodes-from-sampling|})

      ; discovery
      (find.bittorrent.metadata/start-discovery
       {:stateA stateA
        :self-idB self-idB
        :self-id self-id
        :send-krpc-request send-krpc-request
        :socket socket
        :infohashes-from-sampling| (tap infohashes-from-sampling|mult (chan (sliding-buffer 100000)))
        :infohashes-from-listening| (tap infohashes-from-listening|mult (chan (sliding-buffer 100000)))
        :infohashes-from-sybil| (tap infohashes-from-sybil|mult (chan (sliding-buffer 100000)))
        :torrent| torrent|
        :msg|mult msg|mult
        :count-discoveryA count-discoveryA
        :count-discovery-activeA count-discovery-activeA})

      ; process messages
      (let [msg|tap (tap msg|mult (chan (sliding-buffer 512)))]
        (go
          (loop []
            (when-let [{:keys [msg rinfo] :as value} (<! msg|tap)]
              (let [msg-y (some-> (. msg -y) (.toString "utf-8"))
                    msg-q (some-> (. msg -q) (.toString "utf-8"))]
                (cond

                  #_(and (= msg-y "r") (goog.object/getValueByKeys msg "r" "samples"))
                  #_(let [{:keys [id interval nodes num samples]} (:r (js->clj msg :keywordize-keys true))]
                      (doseq [infohashB (->>
                                         (js/Array.from  samples)
                                         (partition 20)
                                         (map #(js/Buffer.from (into-array %))))]
                        #_(println :info_hash (.toString infohashB "hex"))
                        (put! infohash| {:infohashB infohashB
                                         :rinfo rinfo}))

                      (when nodes
                        (put! nodesB| nodes)))


                  #_(and (= msg-y "r") (goog.object/getValueByKeys msg "r" "nodes"))
                  #_(put! nodesB| (.. msg -r -nodes))

                  (and (= msg-y "q")  (= msg-q "ping"))
                  (let [txn-idB  (. msg -t)
                        node-idB (.. msg -a -id)]
                    (if (or (not txn-idB) (not= (.-length node-idB) 20))
                      (do nil :invalid-data)
                      (send-krpc
                       socket
                       (clj->js
                        {:t txn-idB
                         :y "r"
                         :r {:id self-idB #_(gen-neighbor-id node-idB (:self-idB @stateA))}})
                       rinfo)))

                  (and (= msg-y "q")  (= msg-q "find_node"))
                  (let [txn-idB  (. msg -t)
                        node-idB (.. msg -a -id)]
                    (if (or (not txn-idB) (not= (.-length node-idB) 20))
                      (println "invalid query args: find_node")
                      (send-krpc
                       socket
                       (clj->js
                        {:t txn-idB
                         :y "r"
                         :r {:id self-idB #_(gen-neighbor-id node-idB (:self-idB @stateA))
                             :nodes (encode-nodes (take 8 (:routing-table @stateA)))}})
                       rinfo)))

                  (and (= msg-y "q")  (= msg-q "get_peers"))
                  (let [infohashB  (.. msg -a -info_hash)
                        txn-idB (. msg -t)
                        node-idB (.. msg -a -id)
                        tokenB (.slice infohashB 0 4)]
                    (if (or (not txn-idB) (not= (.-length node-idB) 20) (not= (.-length infohashB) 20))
                      (println "invalid query args: get_peers")
                      (do
                        (put! infohashes-from-listening| {:infohashB infohashB
                                                          :rinfo rinfo})
                        (send-krpc
                         socket
                         (clj->js
                          {:t txn-idB
                           :y "r"
                           :r {:id self-idB #_(gen-neighbor-id infohashB (:self-idB @stateA))
                               :nodes (encode-nodes (take 8 (:routing-table @stateA)))
                               :token tokenB}})
                         rinfo))))

                  (and (= msg-y "q")  (= msg-q "announce_peer"))
                  (let [infohashB   (.. msg -a -info_hash)
                        txn-idB (. msg -t)
                        node-idB (.. msg -a -id)
                        tokenB (.slice infohashB 0 4)]

                    (cond
                      (not txn-idB)
                      (println "invalid query args: announce_peer")

                      #_(not= (-> infohashB (.slice 0 4) (.toString "hex")) (.toString tokenB "hex"))
                      #_(println "announce_peer: token and info_hash don't match")

                      :else
                      (do
                        (send-krpc
                         socket
                         (clj->js
                          {:t txn-idB
                           :y "r"
                           :r {:id self-idB}})
                         rinfo)
                        #_(println :info_hash (.toString infohashB "hex"))
                        (put! infohashes-from-listening| {:infohashB infohashB
                                                          :rinfo rinfo}))))

                  :else
                  (do nil)))


              (recur)))))

      stateA)))