(ns find.bittorrent.crawl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
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
   [find.bittorrent.core :refer [hash-key-comparator-fn
                                 send-krpc-request-fn
                                 send-krpc
                                 encode-nodes
                                 decode-nodes
                                 sorted-map-buffer]]
   [find.bittorrent.state-file :refer [save-state load-state]]
   [find.bittorrent.dht]
   [find.bittorrent.find-nodes]
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
          infohash| (chan (sliding-buffer 100))
          infohash|mult (mult infohash|)
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

          nodes-to-sample| (chan (sorted-map-buffer (hash-key-comparator-fn  self-idB))
                                 (filter valid-node?))

          _ (doseq [[id node] (take 8 (shuffle (:routing-table @stateA)))]
              (>! nodes-to-sample| [id node]))

          duration (* 10 60 1000)
          nodes-bootstrap [{:address "router.bittorrent.com"
                            :port 6881}
                           {:address "dht.transmissionbt.com"
                            :port 6881}
                           #_{:address "dht.libtorrent.org"
                              :port 25401}]

          count-torrentsA (atom 0)
          count-infohashesA (atom 0)
          count-discoveryA (atom 0)
          count-discovery-activeA (atom 0)
          count-messagesA (atom 0)
          started-at (t/now)

          procsA (atom [])
          stop (fn []
                 (doseq [stop| @procsA]
                   (close! stop|))
                 (close! msg|)
                 (close! torrent|)
                 (close! infohash|)
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
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop []
            (alt!

              (timeout (* 5 1000))
              ([_]
               (let [state @stateA]
                 (pprint {:count-messages @count-messagesA
                          :count-infohashes @count-infohashesA
                          :count-discovery @count-discoveryA
                          :count-discovery-active @count-discovery-activeA
                          :count-torrents @count-torrentsA
                          :count-sockets @find.bittorrent.metadata/count-socketsA
                          :nodes-to-sample| (count (.-buf nodes-to-sample|))
                          :routing-table (count (:routing-table state))
                          :dht-keyspace (map (fn [[id routing-table]] (count routing-table)) (:dht-keyspace state))
                          :routing-table-find-noded  (count (:routing-table-find-noded state))
                          :routing-table-sampled (count (:routing-table-sampled state))}))
               (recur))

              stop|
              (do :stop)))))

      ; count
      (let [infohash|tap (tap infohash|mult (chan (sliding-buffer 100)))
            torrent|tap (tap torrent|mult (chan (sliding-buffer 100)))]
        (go
          (loop []
            (let [[value port] (alts! [infohash|tap torrent|tap])]
              (when value
                (condp = port
                  infohash|tap
                  (swap! count-infohashesA inc)

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

      ; add new nodes to routing table
      (go
        (loop []
          (when-let [nodesB (<! nodesB|)]
            (let [nodes (decode-nodes nodesB)]
              (>! routing-table-nodes| nodes)
              (>! dht-keyspace-nodes| nodes)
              (<! (a/onto-chan! nodes-to-sample| (map (fn [node] [(:id node) node]) nodes) false)))
            #_(println :nodes-count (count (:routing-table @stateA)))
            (recur))))

      ; ask peers directly, politely for infohashes
      (find.bittorrent.sample-infohashes/start-sampling
       {:stateA stateA
        :self-idB self-idB
        :send-krpc-request send-krpc-request
        :socket socket
        :infohash| infohash|
        :nodes-to-sample| nodes-to-sample|})

      ; discovery
      (find.bittorrent.metadata/start-discovery
       {:stateA stateA
        :self-idB self-idB
        :self-id self-id
        :send-krpc-request send-krpc-request
        :socket socket
        :infohash|mult infohash|mult
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
                         :r {:id (:self-idB @stateA) #_(gen-neighbor-id node-idB (:self-idB @stateA))}})
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
                        (put! infohash| {:infohashB infohashB
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

                      (not= (-> infohashB (.slice 0 4) (.toString "hex")) (.toString tokenB "hex"))
                      (println "announce_peer: token and info_hash don't match")

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
                        (put! infohash| {:infohashB infohashB
                                         :rinfo rinfo}))))

                  :else
                  (do nil)))


              (recur)))))

      stateA)))