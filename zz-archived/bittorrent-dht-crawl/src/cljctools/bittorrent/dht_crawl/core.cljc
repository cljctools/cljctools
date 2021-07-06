(ns cljctools.bittorrent.dht-crawl.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   [clojure.walk]
   #?@(:cljs
       [[goog.string.format :as format]
        [goog.string :refer [format]]
        [goog.object]
        [cljs.reader :refer [read-string]]])

   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.codec.runtime.core :as codec.runtime.core]
   [cljctools.fs.runtime.core :as fs.runtime.core]
   [cljctools.fs.protocols :as fs.protocols]
   [cljctools.datagram-socket.runtime.core :as datagram-socket.runtime.core]
   [cljctools.datagram-socket.protocols :as datagram-socket.protocols]
   [cljctools.datagram-socket.spec :as datagram-socket.spec]

   [cljctools.bencode.core :as bencode.core]
   [cljctools.bittorrent.dht-crawl.impl :refer [hash-key-distance-comparator-fn
                                                send-krpc-request-fn
                                                encode-nodes
                                                decode-nodes
                                                sorted-map-buffer
                                                read-state-file
                                                write-state-file
                                                now
                                                fixed-buf-size
                                                chan-buf]]

   [cljctools.bittorrent.dht-crawl.dht]
   [cljctools.bittorrent.dht-crawl.find-nodes]
   [cljctools.bittorrent.dht-crawl.metadata]
   [cljctools.bittorrent.dht-crawl.sybil]
   [cljctools.bittorrent.dht-crawl.sample-infohashes]))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(declare
 process-socket
 process-print-info
 process-count
 process-messages)

(defn start
  [{:as opts
    :keys [data-dir]}]
  (go
    (let [state-filepath (fs.runtime.core/path-join data-dir "cljctools.bittorrent.dht-crawl.core.json")
          stateA (atom
                  (merge
                   (let [self-idBA  (codec.runtime.core/hex-to-bytes "a8fb5c14469fc7c46e91679c493160ed3d13be3d") #_(bytes.runtime.core/random-bytes 20)]
                     {:self-id (codec.runtime.core/hex-to-string self-idBA)
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

          count-messagesA (atom 0)

          msg| (chan (sliding-buffer 100)
                     (keep (fn [{:keys [msgBA host port]}]
                             (swap! count-messagesA inc)
                             (try
                               {:msg  (->
                                       (bencode.core/decode msgBA)
                                       (clojure.walk/keywordize-keys))
                                :host host
                                :port port}
                               (catch #?(:clj Exception :cljs :default) ex nil)))))

          msg|mult (mult msg|)

          torrent| (chan 5000)
          torrent|mult (mult torrent|)

          send| (chan 1000)

          unique-infohashsesA (atom #{})
          xf-infohash (comp
                       (map (fn [{:keys [infohashBA] :as value}]
                              (assoc value :infohash (codec.runtime.core/hex-to-string infohashBA))))
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
                        (and
                         (:host node)
                         (:port node)
                         (:id node)
                         (not= (:host node) host)
                         (not= (:id node) self-id)
                         #_(not= 0 (js/Buffer.compare (:id node) self-id))
                         (< 0 (:port node) 65536)))

          routing-table-nodes| (chan (sliding-buffer 1024)
                                     (map (fn [nodes] (filter valid-node? nodes))))

          dht-keyspace-nodes| (chan (sliding-buffer 1024)
                                    (map (fn [nodes] (filter valid-node? nodes))))

          xf-node-for-sampling? (comp
                                 (filter valid-node?)
                                 (filter (fn [node] (not (get (:routing-table-sampled @stateA) (:id node)))))
                                 (map (fn [node] [(:id node) node])))

          nodes-to-sample| (chan (sorted-map-buffer 10000 (hash-key-distance-comparator-fn  self-idBA))
                                 xf-node-for-sampling?)

          nodes-from-sampling| (chan (sorted-map-buffer 10000 (hash-key-distance-comparator-fn  self-idBA))
                                     xf-node-for-sampling?)

          duration (* 10 60 1000)
          nodes-bootstrap [{:host "router.bittorrent.com"
                            :port 6881}
                           {:host "dht.transmissionbt.com"
                            :port 6881}
                           #_{:host "dht.libtorrent.org"
                              :port 25401}]

          sybils| (chan 30000)

          procsA (atom [])
          release (fn []
                    (let [stop|s @procsA]
                      (doseq [stop| stop|s]
                        (close! stop|))
                      (close! msg|)
                      (close! torrent|)
                      (close! infohashes-from-sampling|)
                      (close! infohashes-from-listening|)
                      (close! infohashes-from-sybil|)
                      (close! nodes-to-sample|)
                      (close! nodes-from-sampling|)
                      (close! nodesBA|)
                      (a/merge stop|s)))

          ctx {:stateA stateA
               :host host
               :port port
               :data-dir data-dir
               :self-id self-id
               :self-idBA self-idBA
               :msg| msg|
               :msg|mult msg|mult
               :send| send|
               :torrent| torrent|
               :torrent|mult torrent|mult
               :nodes-bootstrap nodes-bootstrap
               :nodes-to-sample| nodes-to-sample|
               :nodes-from-sampling| nodes-from-sampling|
               :routing-table-nodes| routing-table-nodes|
               :dht-keyspace-nodes| dht-keyspace-nodes|
               :nodesBA| nodesBA|
               :infohashes-from-sampling| infohashes-from-sampling|
               :infohashes-from-listening| infohashes-from-listening|
               :infohashes-from-sybil| infohashes-from-sybil|
               :infohashes-from-sampling|mult infohashes-from-sampling|mult
               :infohashes-from-listening|mult infohashes-from-listening|mult
               :infohashes-from-sybil|mult infohashes-from-sybil|mult
               :sybils| sybils|
               :send-krpc-request send-krpc-request
               :count-torrentsA (atom 0)
               :count-infohashes-from-samplingA (atom 0)
               :count-infohashes-from-listeningA (atom 0)
               :count-infohashes-from-sybilA (atom 0)
               :count-discoveryA (atom 0)
               :count-discovery-activeA (atom 0)
               :count-messagesA count-messagesA
               :count-messages-sybilA (atom 0)}]

      (println ::self-id self-id)

      (cljctools.bittorrent.dht-crawl.dht/start-routing-table
       (merge ctx {:routing-table-max-size 128}))


      (cljctools.bittorrent.dht-crawl.dht/start-dht-keyspace
       (merge ctx {:routing-table-max-size 128}))

      (<! (onto-chan! nodes-to-sample|
                      (->> (:routing-table @stateA)
                           (vec)
                           (shuffle)
                           (take 8)
                           (map second))
                      false))

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

      ; socket
      (process-socket ctx)

      ; save state to file periodically
      (go
        (when-not (fs.runtime.core/path-exists? state-filepath)
          (<! (write-state-file state-filepath @stateA)))
        (loop []
          (<! (timeout (* 4.5 1000)))
          (<! (write-state-file state-filepath @stateA))
          (recur)))


      ; print info
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (process-print-info (merge ctx {:stop| stop|})))

      ; count
      (process-count ctx)


      ; after time passes, remove nodes from already-asked tables so they can be queried again
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
         (merge ctx {:stop| stop|})))

      ; periodicaly ask nodes for new nodes
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (cljctools.bittorrent.dht-crawl.find-nodes/start-dht-query
         (merge ctx {:stop| stop|})))

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
          (when-let [nodesBA (<! nodesBA|)]
            (let [nodes (decode-nodes nodesBA)]
              (>! routing-table-nodes| nodes)
              (>! dht-keyspace-nodes| nodes)
              (<! (onto-chan! nodes-to-sample| nodes false)))
            #_(println :nodes-count (count (:routing-table @stateA)))
            (recur))))

      ; ask peers directly, politely for infohashes
      (cljctools.bittorrent.dht-crawl.sample-infohashes/start-sampling
       ctx)

      ; discovery
      (cljctools.bittorrent.dht-crawl.metadata/start-discovery
       (merge ctx
              {:infohashes-from-sampling| (tap infohashes-from-sampling|mult (chan (sliding-buffer 100000)))
               :infohashes-from-listening| (tap infohashes-from-listening|mult (chan (sliding-buffer 100000)))
               :infohashes-from-sybil| (tap infohashes-from-sybil|mult (chan (sliding-buffer 100000)))}))

      ; process messages
      (process-messages
       ctx)

      stateA)))

(defn process-socket
  [{:as opts
    :keys [msg|
           send|
           host
           port]}]
  (let [ex| (chan 1)
        evt| (chan (sliding-buffer 10))
        socket (datagram-socket.runtime.core/create
                {::datagram-socket.spec/host host
                 ::datagram-socket.spec/port port
                 ::datagram-socket.spec/evt| evt|
                 ::datagram-socket.spec/msg| msg|
                 ::datagram-socket.spec/ex| ex|})
        release (fn []
                  (datagram-socket.protocols/close* socket))]
    (go
      (datagram-socket.protocols/listen* socket)
      (<! evt|)
      (println (format "listening on %s:%s" host port))
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

          #_evt|
          #_([{:keys [op] :as value}]
             (when value
               (cond
                 (= op :listening)
                 (println (format "listening on %s:%s" host port)))
               (recur)))

          ex|
          ([ex]
           (when ex
             (release)
             (println ::ex ex)
             (println ::exiting)))))
      (release))))

(defn process-print-info
  [{:as opts
    :keys [stateA
           data-dir
           stop|
           nodes-to-sample|
           nodes-from-sampling|
           sybils|
           count-infohashes-from-samplingA
           count-infohashes-from-listeningA
           count-infohashes-from-sybilA
           count-discoveryA
           count-discovery-activeA
           count-messagesA
           count-torrentsA
           count-messages-sybilA]}]
  (let [started-at (now)
        filepath (fs.runtime.core/path-join data-dir "cljctools.bittorrent.crawl-log.edn")
        _ (fs.runtime.core/remove filepath)
        _ (fs.runtime.core/make-parents filepath)
        writer (fs.runtime.core/writer filepath :append true)
        countA (atom 0)
        release (fn []
                  (fs.protocols/close* writer))]
    (go
      (loop []
        (alt!
          (timeout (* 5 1000))
          ([_]
           (swap! countA inc)
           (let [state @stateA
                 info [[:count @countA]
                       [:infohashes [:total (+ @count-infohashes-from-samplingA @count-infohashes-from-listeningA @count-infohashes-from-sybilA)
                                     :sampling @count-infohashes-from-samplingA
                                     :listening @count-infohashes-from-listeningA
                                     :sybil @count-infohashes-from-sybilA]]
                       [:discovery [:total @count-discoveryA
                                    :active @count-discovery-activeA]]
                       [:torrents @count-torrentsA]
                       [:nodes-to-sample| (count (chan-buf nodes-to-sample|) )
                        :nodes-from-sampling| (count (chan-buf nodes-from-sampling|))]
                       [:messages [:dht @count-messagesA :sybil @count-messages-sybilA]]
                       [:sockets @cljctools.bittorrent.dht-crawl.metadata/count-socketsA]
                       [:routing-table (count (:routing-table state))]
                       [:dht-keyspace (map (fn [[id routing-table]] (count routing-table)) (:dht-keyspace state))]
                       [:routing-table-find-noded  (count (:routing-table-find-noded state))]
                       [:routing-table-sampled (count (:routing-table-sampled state))]
                       [:sybils| (str (- (fixed-buf-size sybils|) (count (chan-buf sybils|))) "/" (fixed-buf-size sybils|))]
                       [:time (str (int (/ (- (now) started-at) 1000 60)) "min")]]]
             (pprint info)
             (fs.protocols/write* writer (with-out-str (pprint info)))
             (fs.protocols/write* writer "\n"))
           (recur))

          stop|
          (do :stop)))
      (release))))

(defn process-count
  [{:as opts
    :keys [infohashes-from-sampling|mult
           infohashes-from-listening|mult
           infohashes-from-sybil|mult
           torrent|mult
           count-infohashes-from-samplingA
           count-infohashes-from-listeningA
           count-infohashes-from-sybilA
           count-torrentsA]}]
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
            (recur)))))))

(defn process-messages
  [{:as opts
    :keys [stateA
           msg|mult
           send|
           self-idBA
           infohashes-from-listening|]}]
  (let [msg|tap (tap msg|mult (chan (sliding-buffer 512)))]
    (go
      (loop []
        (when-let [{:keys [msg host port] :as value} (<! msg|tap)]
          (let [msg-y (some-> (:y msg) (bytes.runtime.core/to-string))
                msg-q (some-> (:q msg) (bytes.runtime.core/to-string))]
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
                (if (or (not txn-idBA) (not= (bytes.runtime.core/alength node-idBA) 20))
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
                (if (or (not txn-idBA) (not= (bytes.runtime.core/alength node-idBA) 20))
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
                    tokenBA (bytes.runtime.core/copy-byte-array infohashBA 0 4)]
                (if (or (not txn-idBA) (not= (bytes.runtime.core/alength node-idBA) 20) (not= (bytes.runtime.core/alength infohashBA) 20))
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
                    tokenBA (bytes.runtime.core/copy-byte-array infohashBA 0 4)]

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


          (recur))))))


(comment

  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/bytes-jvm"}
                      github.cljctools/codec-jvm {:local/root "./cljctools/codec-jvm"}
                      github.cljctools/core-jvm {:local/root "./cljctools/core-jvm"}
                      github.cljctools/datagram-socket-jvm {:local/root "./cljctools/datagram-socket-jvm"}
                      github.cljctools/socket-jvm {:local/root "./cljctools/socket-jvm"}
                      github.cljctools/fs-jvm {:local/root "./cljctools/fs-jvm"}
                      github.cljctools/fs-meta {:local/root "./cljctools/fs-meta"}
                      github.cljctools/transit-jvm {:local/root "./cljctools/transit-jvm"}
                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/spec"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/bencode"}
                      github.cljctools.bittorrent/wire-protocol {:local/root "./bittorrent/wire-protocol"}
                      github.cljctools.bittorrent/dht-crawl {:local/root "./bittorrent/dht-crawl"}}}'

  (require '[cljctools.bittorrent.dht-crawl.core :as dht-crawl.core] :reload-all)

  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/bytes-meta"}
                      github.cljctools/bytes-js {:local/root "./cljctools/bytes-js"}
                      github.cljctools/codec-js {:local/root "./cljctools/codec-js"}
                      github.cljctools/core-js {:local/root "./cljctools/core-js"}
                      github.cljctools/datagram-socket-nodejs {:local/root "./cljctools/datagram-socket-nodejs"}
                      github.cljctools/fs-nodejs {:local/root "./cljctools/fs-nodejs"}
                      github.cljctools/fs-meta {:local/root "./cljctools/fs-meta"}
                      github.cljctools/socket-nodejs {:local/root "./cljctools/socket-nodejs"}
                      github.cljctools/transit-js {:local/root "./cljctools/transit-js"}

                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/spec"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/bencode"}
                      github.cljctools.bittorrent/wire-protocol {:local/root "./bittorrent/wire-protocol"}
                      github.cljctools.bittorrent/dht-crawl {:local/root "./bittorrent/dht-crawl"}}}' \
  -M -m cljs.main \
  -co '{:npm-deps {"randombytes" "2.1.0"
                   "bitfield" "4.0.0"
                   "fs-extra" "9.1.0"}
        :install-deps true
        :analyze-path "./bittorrent/dht-crawl"
        :repl-requires [[cljs.repl :refer-macros [source doc find-doc apropos dir pst]]
                        [cljs.pprint :refer [pprint] :refer-macros [pp]]]}' \
  -ro '{:host "0.0.0.0"
        :port 8899}' \
  --repl-env node --compile cljctools.bittorrent.dht-crawl.core --repl

  (require
   '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                      pub sub unsub mult tap untap mix admix unmix pipe
                                      timeout to-chan  sliding-buffer dropping-buffer
                                      pipeline pipeline-async]]
   '[clojure.core.async.impl.protocols :refer [closed?]])

  (require
   '[cljctools.fs.runtime.core :as fs.runtime.core]
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.codec.runtime.core :as codec.runtime.core]
   '[cljctools.bencode.core :as bencode.core]
   '[cljctools.bittorrent.dht-crawl.core :as dht-crawl.core]
   :reload #_:reload-all)
                   
    (dht-crawl.core/start
     {:data-dir (fs.runtime.core/path-join "./dht-crawl")})
                                                                                                         
    
                                                                                                         
                                                                                                         
  ;
  )



(comment

  (let [c| (chan 10 (map (fn [value]
                           (println [:mapping (.. (Thread/currentThread) (getName))])
                           (inc value))))]

    (go
      (loop [i 10]
        (when (> i 0)
          (<! (timeout 1000))
          (>! c| i)
          (recur (dec i))))
      (close! c|)
      (println [:exit 0]))

    (go
      (loop []
        (when-let [value (<! c|)]
          (println [:took value (.. (Thread/currentThread) (getName))])
          (recur)))
      (println [:exit 1]))

    (go
      (loop []
        (when-let [value (<! c|)]
          (println [:took value (.. (Thread/currentThread) (getName))])
          (recur)))
      (println [:exit 2]))

    (go
      (loop []
        (when-let [value (<! c|)]
          (println [:took value (.. (Thread/currentThread) (getName))])
          (recur)))
      (println [:exit 3]))

    (go
      (loop []
        (when-let [value (<! c|)]
          (println [:took value (.. (Thread/currentThread) (getName))])
          (recur)))
      (println [:exit 4])))

  ;
  )



(comment

  (let [stateA (atom (transient {}))]
    (dotimes [n 8]
      (go
        (loop [i 100]
          (when (> i 0)
            (get @stateA :n)
            (swap! stateA dissoc! :n)
            (swap! stateA assoc! :n i)


            (recur (dec i))))
        (println :done n))))

  ; java.lang.ArrayIndexOutOfBoundsException: Index -2 out of bounds for length 16
  ; at clojure.lang.PersistentArrayMap$TransientArrayMap.doWithout (PersistentArrayMap.java:432)
  ; at clojure.lang.ATransientMap.without (ATransientMap.java:69)
  ; at clojure.core$dissoc_BANG_.invokeStatic (core.clj:3373)


  (time
   (loop [i 10000000
          m (transient {})]
     (if (> i 0)

       (recur (dec i) (-> m
                          (assoc! :a 1)
                          (dissoc! :a)
                          (assoc! :a 2)))
       (persistent! m))))

  ; "Elapsed time: 799.025172 msecs"

  (time
   (loop [i 10000000
          m {}]
     (if (> i 0)

       (recur (dec i) (-> m
                          (assoc :a 1)
                          (dissoc :a)
                          (assoc :a 2)))
       m)))

  ; "Elapsed time: 1361.090409 msecs"

  (time
   (loop [i 10000000
          m (sorted-map)]
     (if (> i 0)

       (recur (dec i) (-> m
                          (assoc :a 1)
                          (dissoc :a)
                          (assoc :a 2)))
       m)))

  ; "Elapsed time: 1847.529152 msecs"

  ;
  )