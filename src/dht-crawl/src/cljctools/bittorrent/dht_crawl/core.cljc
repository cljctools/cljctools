(ns cljctools.bittorrent.dht-crawl.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   #?@(:cljs
       [[goog.string.format :as format]
        [goog.string :refer [format]]
        [goog.object]
        [cljs.reader :refer [read-string]]])

   [cljctools.bytes.core :as bytes.core]
   [cljctools.codec.core :as codec.core]
   [cljctools.fs.core :as fs.core]
   [cljctools.fs.protocols :as fs.protocols]
   [cljctools.datagram-socket.core :as datagram-socket.core]
   [cljctools.datagram-socket.protocols :as datagram-socket.protocols]
   [cljctools.datagram-socket.spec :as datagram-socket.spec]

   [cljctools.bittorrent.bencode.core :as bencode.core]
   [cljctools.bittorrent.dht-crawl.impl :refer [hash-key-distance-comparator-fn
                                                send-krpc-request-fn
                                                encode-nodes
                                                decode-nodes
                                                sorted-map-buffer
                                                read-state-file
                                                write-state-file
                                                now]]

   [cljctools.bittorrent.dht-crawl.dht]
   [cljctools.bittorrent.dht-crawl.find-nodes]
   [cljctools.bittorrent.dht-crawl.metadata]
   #_[cljctools.bittorrent.dht-crawl.sybil]
   [cljctools.bittorrent.dht-crawl.sample-infohashes]))


(defn start
  [{:as opts
    :keys [peer-index
           data-dir]}]
  (go
    (let [state-filepath (fs.core/path-join data-dir "cljctools.bittorrent.dht-crawl.core.json")
          stateA (atom
                  (merge
                   (let [self-idBA  (codec.core/hex-decode "a8fb5c14469fc7c46e91679c493160ed3d13be3d") #_(bytes.core/random-bytes 20)]
                     {:self-id (codec.core/hex-encode-string self-idBA)
                      :self-idBA self-idBA
                      :routing-table (sorted-map)
                      :dht-keyspace {}
                      :routing-table-sampled {}
                      :routing-table-find-noded {}})
                   (<! (read-state-file state-filepath))))

          self-id (:self-id @stateA)
          self-idBA (:self-idBA @stateA)

          port 6881
          host "0.0.0.0"

          socket-ex| (chan 1)
          socket-evt| (chan (sliding-buffer 10))

          msg| (chan (sliding-buffer 100)
                     (keep (fn [{:keys [msgBA host port]}]
                             (try
                               {:msg (bencode.core/decode msgBA)
                                :host host
                                :port port}
                               (catch #?(:clj Exception :cljs :default) ex nil)))))

          msg|mult (mult msg|)

          socket (datagram-socket.core/create
                  {::datagram-socket.spec/host host
                   ::datagram-socket.spec/port port
                   ::datagram-socket.spec/evt| socket-evt|
                   ::datagram-socket.spec/msg| msg|
                   ::datagram-socket.spec/ex| socket-ex|})

          torrent| (chan 5000)
          torrent|mult (mult torrent|)

          send| (chan 100)

          unique-infohashsesA (atom #{})
          xf-infohash (comp
                       (map (fn [{:keys [infohashBA] :as value}]
                              (assoc value :infohash (codec.core/hex-encode-string infohashBA))))
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

          nodesBA| (chan (sliding-buffer 100))

          send-krpc-request (send-krpc-request-fn {:msg|mult msg|mult
                                                   :send| send|})

          valid-node? (fn [node]
                        (and (not= (:host node) host)
                             (not= (:id node) self-id)
                             #_(not= 0 (js/Buffer.compare (:id node) self-id))
                             (< 0 (:port node) 65536)))

          routing-table-nodes| (chan (sliding-buffer 1024)
                                     (map (fn [nodes] (filter valid-node? nodes))))

          dht-keyspace-nodes| (chan (sliding-buffer 1024)
                                    (map (fn [nodes] (filter valid-node? nodes))))


          _ (cljctools.bittorrent.dht-crawl.dht/start-routing-table
             {:stateA stateA
              :self-idBA self-idBA
              :nodes| routing-table-nodes|
              :send-krpc-request send-krpc-request
              :routing-table-max-size 128})


          _ (cljctools.bittorrent.dht-crawl.dht/start-dht-keyspace
             {:stateA stateA
              :self-idBA self-idBA
              :nodes| dht-keyspace-nodes|
              :send-krpc-request send-krpc-request
              :routing-table-max-size 128})

          xf-node-for-sampling? (comp
                                 (filter valid-node?)
                                 (filter (fn [node] (not (get (:routing-table-sampled @stateA) (:id node)))))
                                 (map (fn [node] [(:id node) node])))

          nodes-to-sample| (chan (sorted-map-buffer 10000 (hash-key-distance-comparator-fn  self-idBA))
                                 xf-node-for-sampling?)

          nodes-from-sampling| (chan (sorted-map-buffer 10000 (hash-key-distance-comparator-fn  self-idBA))
                                     xf-node-for-sampling?)

          _ (<! (onto-chan! nodes-to-sample|
                            (->> (:routing-table @stateA)
                                 (shuffle)
                                 (take 8)
                                 (map second))
                            false))

          duration (* 10 60 1000)
          nodes-bootstrap [{:host "router.bittorrent.com"
                            :port 6881}
                           {:host "dht.transmissionbt.com"
                            :port 6881}
                           #_{:host "dht.libtorrent.org"
                              :port 25401}]

          count-torrentsA (atom 0)
          count-infohashes-from-samplingA (atom 0)
          count-infohashes-from-listeningA (atom 0)
          count-infohashes-from-sybilA (atom 0)
          count-discoveryA (atom 0)
          count-discovery-activeA (atom 0)
          count-messagesA (atom 0)
          count-messages-sybilA (atom 0)
          started-at (now)

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
                 (close! nodes-from-sampling|)
                 (close! nodesBA|)
                 (.close socket)
                 (a/merge @procsA))]

      (println ::self-id self-id)

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

      (go
        (loop []
          (alt!
            send|
            ([{:keys [msg host port] :as value}]
             (when value
               (datagram-socket.protocols/send*
                socket
                (bencode.core/encode msg)
                {:host host
                 :port port})
               (recur)))

            socket-evt|
            ([{:keys [op] :as value}]
             (when value
               (cond
                 (= op :listening)
                 (println (format "listening on %s:%s" host port)))
               (recur)))

            socket-ex|
            ([ex]
             (when ex
               (println ::ex ex)
               (println ::exiting))))))

      ; save state to file periodically
      (go
        (when-not (fs.core/path-exists? state-filepath)
          (<! (write-state-file state-filepath @stateA)))
        (loop []
          (<! (timeout (* 4.5 1000)))
          (<! (write-state-file state-filepath @stateA))
          (recur)))


      ; print info
      (let [stop| (chan 1)
            filepath (fs.core/path-join data-dir "cljctools.bittorrent.crawl-log.edn")
            _ (fs.core/remove filepath)
            _ (fs.core/make-parents filepath)
            writer (fs.core/writer filepath :append true)
            release (fn []
                      (fs.protocols/close* writer))]
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
                           [:sockets @cljctools.bittorrent.dht-crawl.metadata/count-socketsA]
                           [:routing-table (count (:routing-table state))]
                           [:dht-keyspace (map (fn [[id routing-table]] (count routing-table)) (:dht-keyspace state))]
                           [:routing-table-find-noded  (count (:routing-table-find-noded state))]
                           [:routing-table-sampled (count (:routing-table-sampled state))]
                           [:sybils| (str (- (.. sybils| -buf -n) (count (.-buf sybils|))) "/" (.. sybils| -buf -n))]
                           [:time (str (int (/ (- (now) started-at) 1000 60)) "min")]]]
                 (pprint info)
                 (fs.protocols/write* writer (with-out-str (pprint info)))
                 (fs.protocols/write* writer "\n"))
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
                 (when (> (- (now) timestamp) (* 5 60 1000))
                   (swap! stateA update-in [:routing-table-sampled] dissoc id)))

               (doseq [[id {:keys [timestamp interval]}] (:routing-table-find-noded @stateA)]
                 (when (or
                        (and interval (> (now) (+ timestamp (* interval 1000))))
                        (> (- (now) timestamp) (* 5 60 1000)))
                   (swap! stateA update-in [:routing-table-find-noded] dissoc id)))
               (recur (timeout (* 10 1000))))

              stop|
              (do :stop)))))

      ; very rarely ask bootstrap servers for nodes
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (cljctools.bittorrent.dht-crawl.find-nodes/start-bootstrap-query
         {:stateA stateA
          :self-idBA self-idBA
          :nodes-bootstrap nodes-bootstrap
          :send-krpc-request send-krpc-request
          :nodesBA| nodesBA|
          :stop|  stop|}))

      ; periodicaly ask nodes for new nodes
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (cljctools.bittorrent.dht-crawl.find-nodes/start-dht-query
         {:stateA stateA
          :self-idBA self-idBA
          :send-krpc-request send-krpc-request
          :nodesBA| nodesBA|
          :stop| stop|}))

      ; start sybil
      #_(let [stop| (chan 1)]
          (swap! procsA conj stop|)
          (cljctools.bittorrent.dht-crawl.sybil/start
           {:stateA stateA
            :nodes-bootstrap nodes-bootstrap
            :sybils| sybils|
            :infohash| infohashes-from-sybil|
            :stop| stop|
            :count-messages-sybilA count-messages-sybilA}))

      ; add new nodes to routing table
      (go
        (loop []
          (when-let [nodesB (<! nodesBA|)]
            (let [nodes (decode-nodes nodesB)]
              (>! routing-table-nodes| nodes)
              (>! dht-keyspace-nodes| nodes)
              (<! (onto-chan! nodes-to-sample| nodes false)))
            #_(println :nodes-count (count (:routing-table @stateA)))
            (recur))))

      ; ask peers directly, politely for infohashes
      (cljctools.bittorrent.dht-crawl.sample-infohashes/start-sampling
       {:stateA stateA
        :self-idBA self-idBA
        :send-krpc-request send-krpc-request
        :infohash| infohashes-from-sampling|
        :nodes-to-sample| nodes-to-sample|
        :nodes-from-sampling| nodes-from-sampling|})

      ; discovery
      (cljctools.bittorrent.dht-crawl.metadata/start-discovery
       {:stateA stateA
        :self-idBA self-idBA
        :self-id self-id
        :send-krpc-request send-krpc-request
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
            (when-let [{:keys [msg host port] :as value} (<! msg|tap)]
              (let [msg-y (some-> (:y msg) (bytes.core/to-string))
                    msg-q (some-> (:q msg) (bytes.core/to-string))]
                (cond

                  #_(and (= msg-y "r") (goog.object/getValueByKeys msg "r" "samples"))
                  #_(let [{:keys [id interval nodes num samples]} (:r (js->clj msg :keywordize-keys true))]
                      (doseq [infohashBA (->>
                                          (js/Array.from  samples)
                                          (partition 20)
                                          (map #(js/Buffer.from (into-array %))))]
                        #_(println :info_hash (.toString infohashBA "hex"))
                        (put! infohash| {:infohashBA infohashBA
                                         :rinfo rinfo}))

                      (when nodes
                        (put! nodesBA| nodes)))


                  #_(and (= msg-y "r") (goog.object/getValueByKeys msg "r" "nodes"))
                  #_(put! nodesBA| (.. msg -r -nodes))

                  (and (= msg-y "q")  (= msg-q "ping"))
                  (let [txn-idBA  (:t msg)
                        node-idBA (get-in msg [:a :id])]
                    (if (or (not txn-idBA) (not= (bytes.core/alength node-idBA) 20))
                      (do nil :invalid-data)
                      (put! send|
                            {:msg  {:t txn-idBA
                                    :y "r"
                                    :r {:id self-idBA #_(gen-neighbor-id node-idB (:self-idBA @stateA))}}
                             :host host
                             :port port})))

                  (and (= msg-y "q")  (= msg-q "find_node"))
                  (let [txn-idBA  (:t msg)
                        node-idBA (get-in msg [:a :id])]
                    (if (or (not txn-idBA) (not= (bytes.core/alength node-idBA) 20))
                      (println "invalid query args: find_node")
                      (put! send|
                            {:msg {:t txn-idBA
                                   :y "r"
                                   :r {:id self-idBA #_(gen-neighbor-id node-idB (:self-idBA @stateA))
                                       :nodes (encode-nodes (take 8 (:routing-table @stateA)))}}
                             :host host
                             :port port})))

                  (and (= msg-y "q")  (= msg-q "get_peers"))
                  (let [infohashBA (get-in msg [:a :info_hash])
                        txn-idBA (:t msg)
                        node-idBA (get-in msg [:a :id])
                        tokenBA (-> (bytes.core/buffer-wrap infohashBA 0 4) (bytes.core/to-byte-array))]
                    (if (or (not txn-idBA) (not= (bytes.core/alength node-idBA) 20) (not= (bytes.core/alength infohashBA) 20))
                      (println "invalid query args: get_peers")
                      (do
                        (put! infohashes-from-listening| {:infohashBA infohashBA})
                        (put! send|
                              {:msg {:t txn-idBA
                                     :y "r"
                                     :r {:id self-idBA #_(gen-neighbor-id infohashBA (:self-idBA @stateA))
                                         :nodes (encode-nodes (take 8 (:routing-table @stateA)))
                                         :token tokenBA}}
                               :host host
                               :port port}))))

                  (and (= msg-y "q")  (= msg-q "announce_peer"))
                  (let [infohashBA  (get-in msg [:a :info_hash])
                        txn-idBA (:t msg)
                        node-idBA (get-in msg [:a :id])
                        tokenBA (-> (bytes.core/buffer-wrap infohashBA 0 4) (bytes.core/to-byte-array))]

                    (cond
                      (not txn-idBA)
                      (println "invalid query args: announce_peer")

                      #_(not= (-> infohashBA (.slice 0 4) (.toString "hex")) (.toString tokenB "hex"))
                      #_(println "announce_peer: token and info_hash don't match")

                      :else
                      (do
                        (put! send|
                              {:msg {:t tokenBA
                                     :y "r"
                                     :r {:id self-idBA}}
                               :host host
                               :port port})
                        #_(println :info_hash (.toString infohashBA "hex"))
                        (put! infohashes-from-listening| {:infohashBA infohashBA}))))

                  :else
                  (do nil)))


              (recur)))))

      stateA)))


(comment

  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}
                      github.cljctools/codec-jvm {:local/root "./cljctools/src/codec-jvm"}
                      github.cljctools/core-jvm {:local/root "./cljctools/src/core-jvm"}
                      github.cljctools/datagram-socket-jvm {:local/root "./cljctools/src/datagram-socket-jvm"}
                      github.cljctools/socket-jvm {:local/root "./cljctools/src/socket-jvm"}
                      github.cljctools/fs-jvm {:local/root "./cljctools/src/fs-jvm"}
                      github.cljctools/fs-meta {:local/root "./cljctools/src/fs-meta"}
                      github.cljctools/transit-jvm {:local/root "./cljctools/src/transit-jvm"}
                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/src/spec"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.bittorrent/wire-protocol {:local/root "./bittorrent/src/wire-protocol"}
                      github.cljctools.bittorrent/dht-crawl {:local/root "./bittorrent/src/dht-crawl"}}}'
  
  (require '[cljctools.bittorrent.dht-crawl.core :as dht-crawl.core] :reload-all)
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}
                      github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}
                      github.cljctools/codec-js {:local/root "./cljctools/src/codec-js"}
                      github.cljctools/core-js {:local/root "./cljctools/src/core-js"}
                      github.cljctools/datagram-socket-nodejs {:local/root "./cljctools/src/datagram-socket-nodejs"}
                      github.cljctools/fs-nodejs {:local/root "./cljctools/src/fs-nodejs"}
                      github.cljctools/fs-meta {:local/root "./cljctools/src/fs-meta"}
                      github.cljctools/socket-nodejs {:local/root "./cljctools/src/socket-nodejs"}
                      github.cljctools/transit-js {:local/root "./cljctools/src/transit-js"}

                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/src/spec"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.bittorrent/wire-protocol {:local/root "./bittorrent/src/wire-protocol"}
                      github.cljctools.bittorrent/dht-crawl {:local/root "./bittorrent/src/dht-crawl"}}}' \
  -M -m cljs.main -co '{:npm-deps {"randombytes" "2.1.0"
                                   "bitfield" "4.0.0"
                                   "fs-extra" "9.1.0"}
                        :install-deps true} '\
  --repl-env node --compile cljctools.bittorrent.dht-crawl.core --repl
   
                                                                                                        
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])

    (require '[cljctools.bittorrent.dht-crawl.core :as dht-crawl.core] :reload))
  ;
  )