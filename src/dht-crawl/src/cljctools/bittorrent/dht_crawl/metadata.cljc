(ns cljctools.bittorrent.dht-crawl.metadata
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   [clojure.walk]

   [cljctools.bytes.core :as bytes.core]
   [cljctools.codec.core :as codec.core]
   [cljctools.socket.core :as socket.core]
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols]
   [cljctools.bittorrent.bencode.core :as bencode.core]
   [cljctools.bittorrent.wire-protocol.core :as wire-protocol.core]
   [cljctools.bittorrent.spec :as bittorrent.spec]
   [cljctools.bittorrent.dht-crawl.impl :refer [hash-key-distance-comparator-fn
                                                decode-nodes
                                                decode-values
                                                sorted-map-buffer
                                                now]]))

(def count-socketsA (atom 0))

(defn request-metadata
  [{:keys [host port]} idBA infohashBA cancel|]
  (go
    (let [timeout| (timeout 4000)
          ex| (chan 1)
          result| (chan 1)

          evt| (chan (sliding-buffer 10))
          msg| (chan 100)

          send| (chan 100)

          recv| (chan 100)

          socket (socket.core/create
                  {::socket.spec/port port
                   ::socket.spec/host host
                   ::socket.spec/evt| evt|
                   ::socket.spec/msg| msg|
                   ::socket.spec/ex| ex|})

          wire (wire-protocol.core/create
                {::send| send|
                 ::recv| recv|
                 ::metadata| result|
                 ::ex| ex|
                 ::bittorrent.spec/infohashBA infohashBA
                 ::bittorrent.spec/peer-idBA idBA})

          release (fn []
                    (swap! count-socketsA dec)
                    (socket.protocols/close* socket)
                    (close! msg|)
                    (close! send|)
                    (close! recv|))]

      (take! evt| prn)

      (go
        (loop []
          (when-let [value (<! send|)]
            (socket.protocols/send* socket value)
            (recur))))

      (go
        (loop []
          (alt!
            (timeout 2000)
            ([_]
             (>! ex| (ex-info "socket timeout: no messages" {} nil)))

            msg|
            ([value]
             (when value
               (>! recv| value)
               (recur))))))

      (swap! count-socketsA inc)
      (socket.protocols/connect* socket)

      (alt!

        [timeout| cancel| ex|]
        ([value port]
         (when value
           (println (ex-message value)))
         (release)
         nil)

        result|
        ([metadataBA]
         (let [metadata-info (->
                              (bencode.core/decode metadataBA)
                              (clojure.walk/keywordize-keys)
                              :info)
               metadata (clojure.walk/postwalk
                         (fn [form]
                           (cond
                             (bytes.core/byte-array? form)
                             (bytes.core/to-string form)

                             :else form))
                         (select-keys metadata-info [:name :files :name.utf-8 :length]))]
           (release)
           metadata))))))

(defn find-metadata
  [{:keys [send-krpc-request routing-table self-idBA self-id infohashBA cancel|]}]
  (go
    (let [seeders-countA (atom 0)
          result| (chan 1)
          cancel-channelsA (atom (transient []))

          valid-ip? (fn [node]
                      (and
                       (not= self-id (:id node))
                       (not= (:host node) "0.0.0.0")
                       (< 0 (:port node) 65536)))

          unique-seedersA (atom (transient #{}))

          unique-seeder? (fn [seeder]
                           (not (get @unique-seedersA seeder)))

          seeders| (chan (sliding-buffer 256))
          nodes| (chan (sorted-map-buffer 1024 (hash-key-distance-comparator-fn infohashBA)))
          nodes-seeders| (chan (sliding-buffer 256))
          seeder| (chan 1)


          routing-table-nodes| (chan (sorted-map-buffer 128 (hash-key-distance-comparator-fn infohashBA)
                                                        #_(fn [id1 id2]
                                                            (distance-compare
                                                             (xor-distance infohashB (js/Buffer.from id1 "hex"))
                                                             (xor-distance infohashB (js/Buffer.from id2 "hex")))
                                                            #_(cond
                                                                (and (not (:idB node1)) (not (:idB node2))) 0
                                                                (and (not (:idB node1)) (:idB node2)) -1
                                                                (and (not (:idB node2)) (:idB node1)) 1
                                                                :else (distance-compare
                                                                       (xor-distance infohashB (:idB node1))
                                                                       (xor-distance infohashB (:idB node2)))))))

          _ (<! (onto-chan! routing-table-nodes| (sort-by first (hash-key-distance-comparator-fn infohashBA) routing-table) false))

          send-get-peers (fn [node]
                           (go
                             (alt!
                               (send-krpc-request
                                {:t (bytes.core/random-bytes 4)
                                 :y "q"
                                 :q "get_peers"
                                 :a {:id self-idBA
                                     :info_hash infohashBA}}
                                node
                                (timeout 2000))
                               ([value]
                                (when value
                                  (let [{:keys [msg]} value]
                                    (:r msg)))))))

          request-metadata* (fn [node]
                              (when-not (closed? seeders|)
                                (let [cancel| (chan 1)
                                      out| (chan 1)]
                                  (swap! cancel-channelsA conj! cancel|)
                                  (take! (request-metadata node self-idBA infohashBA cancel|)
                                         (fn [metadata]
                                           (when metadata
                                             (let [result (merge
                                                           metadata
                                                           {:infohash (codec.core/hex-encode-string infohashBA)
                                                            :seeder-count @seeders-countA})]
                                               (put! result| result)
                                               (put! out| result)))
                                           (close! out|)))
                                  out|)))


          release (fn []
                    (close! seeders|)
                    (close! seeder|)
                    (close! nodes|)
                    (doseq [cancel| (persistent! @cancel-channelsA)]
                      (close! cancel|)))]

      (go
        (loop [n 8
               i n
               ts (now)
               time-total 0]
          (let [timeout| (when (and (== i 0) (< time-total 1000))
                           (timeout 1000))
                [value port] (alts! (concat
                                     [seeders|]
                                     (if timeout|
                                       [timeout|]
                                       [#_nodes-seeders|
                                        nodes|
                                        routing-table-nodes|]))
                                    :priority true)]
            (when (or value (= port timeout|))
              (cond

                (= port seeders|)
                (let [seeders value]
                  #_(println :seeders| (count seeders))
                  (doseq [seeder seeders]
                    (swap! unique-seedersA conj! seeder)
                    (>! seeder| seeder))
                  (recur n i ts time-total))

                (= port timeout|)
                (do
                  :cool-down
                  (recur n n (now) 0))

                (or (= port nodes|) (= port routing-table-nodes|) (= port nodes-seeders|))
                (let [[id node] value]
                  (take! (send-get-peers node)
                         (fn [{:keys [token values nodes]}]
                           (cond
                             values
                             (let [seeders (->>
                                            (decode-values values)
                                            (sequence
                                             (comp
                                              (filter valid-ip?)
                                              (filter unique-seeder?))))]
                               (swap! seeders-countA + (count seeders))
                               #_(println :seeders-response (count seeders))
                               (put! seeders| seeders)
                               (onto-chan! nodes-seeders| seeders false))

                             nodes
                             (let [nodes (->>
                                          (decode-nodes nodes)
                                          (filter valid-ip?))]
                               (onto-chan! nodes| (map (fn [node] [(:id node) node]) nodes) false)))))
                  (recur n (mod (inc i) n) (now) (+ time-total (- (now) ts)))))))))

      (go
        (loop [n 8
               i n
               batch (transient [])]
          (cond
            (== i 0)
            (do
              (<! (a/map (constantly nil) (persistent! batch)))
              (recur n n (transient [])))

            :else
            (when-let [seeder (<! seeder|)]
              #_(println :seeder|)
              (recur n (mod (inc i) n) (conj! batch (request-metadata* seeder)))))))

      (alt!
        [(timeout (* 15 1000)) cancel|]
        ([_ _]
         (release)
         nil)

        result|
        ([value]
         (release)
         value)))))

(defn start-discovery
  [{:as opts
    :keys [stateA
           self-idBA
           self-id
           send-krpc-request
           infohashes-from-sampling|
           infohashes-from-listening|
           infohashes-from-sybil|
           torrent|

           count-discoveryA
           count-discovery-activeA]}]

  (let [in-processA (atom (transient {}))
        already-searchedA (atom (transient #{}))
        in-progress| (chan 80)]
    (go
      (loop []
        (let [[value port] (alts! [infohashes-from-sybil|
                                   infohashes-from-sampling|
                                   infohashes-from-listening|]
                                  :priority true)]
          (when-let [{:keys [infohash infohashBA]} value]
            (when-not (or (get @in-processA infohash)
                          (get @already-searchedA infohash))
              (>! in-progress| infohashBA)
              (let [state @stateA
                    closest-key (->>
                                 (keys (:dht-keyspace state))
                                 (concat [self-id])
                                 (sort-by identity (hash-key-distance-comparator-fn infohashBA))
                                 (first))
                    closest-routing-table (if (= closest-key self-id)
                                            (:routing-table state)
                                            (get (:dht-keyspace state) closest-key))
                    find_metadata| (find-metadata {:routing-table closest-routing-table
                                                   :send-krpc-request send-krpc-request
                                                   :self-idBA self-idBA
                                                   :self-id self-id
                                                   :infohashBA infohashBA
                                                   :cancel| (chan 1)})]
                (swap! in-processA assoc! infohash find_metadata|)
                (swap! already-searchedA conj! infohash)
                (swap! count-discoveryA inc)
                (swap! count-discovery-activeA inc)
                #_(let [metadata (<! find_metadata|)]
                    (when metadata
                      (put! torrent| metadata)
                      (pprint (select-keys metadata [:seeder-count])))
                    (swap! count-discovery-activeA dec)
                    (swap! in-processA dissoc infohash)
                    (println :dicovery-done))
                (take! find_metadata|
                       (fn [metadata]
                         (when metadata
                           (put! torrent| metadata)
                           #_(pprint (select-keys metadata [:seeder-count])))
                         (take! in-progress| (constantly nil))

                         (swap! count-discovery-activeA dec)
                         (swap! in-processA dissoc! infohash)))))
            (recur)))))))

#_(defn request-metadata-multiple
    [{:keys [address port] :as node} idB infohashes cancel|]
    (go
      (let [time-out 10000
            error| (chan 1)
            result| (chan 100)
            socket (net.Socket.)
            infohashes| (chan 100)
            release (fn []
                      (close! infohashes|)
                      (close! result|)
                      (.destroy socket))]
        (<! (onto-chan! infohashes| infohashes true))
        (swap! count-socketsA inc)
        (doto socket
          (.on "error" (fn [error]
                         (println "request-metadata-socket error" error)
                         (close! error|)))
          (.on "close" (fn [hadError]
                         (swap! count-socketsA dec)))
          (.on "timeout" (fn []
                           (println "request-metadata-socket timeout")
                           (close! error|)))
          (.setTimeout 4000))
        (.connect socket port address
                  (fn []
                    (go
                      (loop []
                        (when-let [infohashB (<! infohashes|)]
                          (let [wire (BittorrrentProtocol.)
                                out| (chan 1)]
                            (-> socket
                                (.pipe wire)
                                (.pipe socket))
                            (.use wire (ut_metadata))
                            (.handshake wire infohashB idB (clj->js {:dht true}))
                            #_(println :handshaking (.toString infohashB "hex"))
                            (.on wire "handshake"
                                 (fn [infohash peer-id]
                                   #_(println "request-metadata-socket handshake" infohash)
                                   (.. wire -ut_metadata (fetch))))
                            (.on (. wire -ut_metadata) "metadata"
                                 (fn [data]
                                   #_(println "request-metadata-socket metadata")
                                   (let [metadata-info (.-info (.decode bencode data))
                                         metadata  (clojure.walk/postwalk
                                                    (fn [form]
                                                      (cond
                                                        (instance? js/Buffer form)
                                                        (.toString form "utf-8")

                                                        :else form))
                                                    (select-keys (js->clj metadata-info) ["name" "files" "name.utf-8" "length"]))]
                                     #_(println (js-keys metadata-info))
                                     #_(println :metadata (.. metadata -name (toString "utf-8")))
                                     #_(pprint metadata)
                                     (put! out| metadata))))
                            (let [metadata (<! out|)]
                              (.unpipe socket wire)
                              (.unpipe wire socket)
                              (.destroy wire)
                              (>! result| metadata)))
                          (recur)))
                      (close! result|))))
        (alt!
          [(timeout time-out) cancel| error|]
          ([_ _]
           (release)
           (<! (a/into [] result|)))

          (a/into [] result|)
          ([value]
           (release)
           value)))))