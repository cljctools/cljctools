(ns find.app.bittorrent
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.pprint :refer [pprint]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.string]
   [clojure.walk]
   [clojure.set]
   [tick.alpha.api :as t]
   [cognitect.transit :as transit]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]))

(defonce fs (js/require "fs-extra"))
(defonce path (js/require "path"))
(defonce BittorrrentProtocol (js/require "bittorrent-protocol"))
(defonce ut_metadata (js/require "ut_metadata"))
(defonce MagnetURI (js/require "magnet-uri"))
(defonce crypto (js/require "crypto"))
(defonce bencode (js/require "bencode"))
(defonce dgram (js/require "dgram"))
(defonce net (js/require "net"))

(defn gen-neighbor-id
  [target-idB node-idB]
  (->>
   [(.slice target-idB 0  10) (.slice node-idB 10)]
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

(defn decode-nodes
  [nodesB]
  (try
    (for [i (range 0 (.-length nodesB) 26)]
      (let [idB (.slice nodesB i (+ i 20))]
        {:id (.toString idB "hex")
         :idB idB
         :address (str (aget nodesB (+ i 20)) "."
                       (aget nodesB (+ i 21)) "."
                       (aget nodesB (+ i 22)) "."
                       (aget nodesB (+ i 23)))
         :port (.readUInt16BE nodesB (+ i 24))}))
    (catch js/Error e [])))

(defn decode-values
  [values]
  (->>
   values
   (sequence
    (comp
     (filter (fn [peer-infoB] (instance? js/Buffer peer-infoB)))
     (map
      (fn [peer-infoB]
        {:address (str (aget peer-infoB 0) "."
                       (aget peer-infoB 1) "."
                       (aget peer-infoB 2) "."
                       (aget peer-infoB 3))
         :port (.readUInt16BE peer-infoB 4)}))))))

(defn decode-samples
  [samplesB]
  (->>
   (js/Array.from samplesB)
   (partition 20)
   (map #(js/Buffer.from (into-array %)))))

(defn send-krpc
  [socket msg rinfo]
  (let [msgB (.encode bencode msg)]
    (.send socket msgB 0 (.-length msgB) (. rinfo -port) (. rinfo -address))))


(defn xor-distance
  [buffer1B buffer2B]
  (when-not (= (.-length buffer1B) (.-length buffer2B))
    (throw (ex-info "xor-distance: buffers should have same length" {})))
  (reduce
   (fn [result i]
     (aset result i (bit-xor (aget buffer1B i) (aget buffer2B i)))
     result)
   (js/Buffer.allocUnsafe (.-length buffer1B))
   (range 0 (.-length buffer1B))))

(defn distance-compare
  [distance1B distance2B]
  (when-not (= (.-length distance1B) (.-length distance2B))
    (throw (ex-info "distance-compare: buffers should have same length" {})))
  (reduce
   (fn [result i]
     (let [a (aget distance1B i)
           b (aget distance2B i)]
       (cond
         (= a b) 0
         (< a b) (reduced -1)
         (> a b) (reduced 1))))
   0
   (range 0 (.-length distance1B))))

(def count-socketsA (atom 0))

(defn request-metadata
  [{:keys [address port]} idB infohashB cancel|]
  (go
    (let [time-out 4000
          error| (chan 1)
          result| (chan 1)
          socket (net.Socket.)
          release (fn []
                    (.destroy socket))]
      (swap! count-socketsA inc)
      (doto socket
        (.on "error" (fn [error]
                       #_(println "request-metadata-socket error" error)
                       (close! error|)))
        (.on "close" (fn [hadError]
                       (swap! count-socketsA dec)))
        (.on "timeout" (fn []
                         #_(println "request-metadata-socket timeout")
                         (close! error|)))
        (.setTimeout 1000))
      (.connect socket port address
                (fn []
                  (let [wire (BittorrrentProtocol.)]
                    (-> socket
                        (.pipe wire)
                        (.pipe socket))
                    (.use wire (ut_metadata))
                    (.handshake wire infohashB idB (clj->js {:dht true}))
                    (.on wire "handshake"
                         (fn [infohash peer-id]
                           #_(println "request-metadata-socket handshake" infohash)
                           (.. wire -ut_metadata (fetch))))
                    (.on (. wire -ut_metadata) "metadata"
                         (fn [data]
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
                             (put! result| metadata)))))))
      (alt!

        (timeout time-out)
        ([_]
         (release)
         nil)

        cancel|
        ([_]
         (release)
         nil)

        error|
        ([value]
         (release)
         nil)

        result|
        ([value]
         (release)
         value)))))

(defn request-metadata-multiple
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
      (<! (a/onto-chan! infohashes| infohashes true))
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

(defn find-metadata
  [{:keys [send-krpc-request socket port routing-table  msg|mult node-idB infohashB cancel|]}]
  (go
    (let [seeders-countA (atom 0)
          result| (chan 1)
          nodesB| (chan (sliding-buffer 256))
          seeders| (chan (sliding-buffer 128))
          cancel-channelsA (atom [])

          sort-closest (fn [nodes]
                         (->>
                          nodes
                          (sort-by identity
                                   (fn [node1 node2]
                                     (distance-compare
                                      (xor-distance infohashB (:idB node1))
                                      (xor-distance infohashB (:idB node2)))
                                     #_(cond
                                         (and (not (:idB node1)) (not (:idB node2))) 0
                                         (and (not (:idB node1)) (:idB node2)) -1
                                         (and (not (:idB node2)) (:idB node1)) 1
                                         :else (distance-compare
                                                (xor-distance infohashB (:idB node1))
                                                (xor-distance infohashB (:idB node2))))))))

          nodesA (atom (take 8 (sort-closest (vals routing-table))))

          send-get-peers (fn [node]
                           (go
                             (alt!
                               (send-krpc-request
                                socket
                                (clj->js
                                 {:t (.randomBytes crypto 4)
                                  :y "q"
                                  :q "get_peers"
                                  :a {:id node-idB
                                      :info_hash infohashB}})
                                (clj->js node)
                                (timeout 2000))
                               ([value]
                                (when value
                                  (let [{:keys [msg rifno]} value]
                                    (:r (js->clj (:msg value) :keywordize-keys true))))))))

          request-metadata* (fn [node]
                              (let [cancel| (chan 1)]
                                (swap! cancel-channelsA conj cancel|)
                                (take! (request-metadata node node-idB infohashB cancel|)
                                       (fn [metadata]
                                         (when metadata
                                           (put! result| (merge
                                                          metadata
                                                          {:infohash (.toString infohashB "hex")
                                                           :seeder-count @seeders-countA})))))))
          valid-ip? (fn [node]
                      (and (not= (:address node) "0.0.0.0")
                           (< 0 (:port node) 65536)))

          procsA (atom [])
          release (fn []
                    (doseq [stop| @procsA]
                      (close! stop|))
                    (close! nodesB|)
                    (close! seeders|)
                    (doseq [cancel| @cancel-channelsA]
                      (close! cancel|)))]

      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop [timeout| (timeout 0)]
            (let [[value port] (alts! [timeout| stop|])]
              (when (= port timeout|)
                (let [nodes-sorted (sort-closest @nodesA)]
                  (doseq [node (take 8 nodes-sorted)]
                    (take! (send-get-peers node)
                           (fn [{:keys [token values nodes]}]
                             (go
                               (cond
                                 values
                                 (let [seeders (->>
                                                (decode-values values)
                                                (filter valid-ip?))]
                                   (swap! seeders-countA + (count seeders) 1)
                                   #_(swap! nodesA concat seeders)
                                   #_(request-metadata* node)
                                   (doseq [seeder (take 8 seeders)]
                                     (put! seeders| seeder)
                                     #_(<! (timeout 50))
                                     #_(request-metadata* seeder)))

                                 nodes
                                 (let [nodes (->>
                                              (decode-nodes nodes)
                                              (filter valid-ip?))]
                                   (swap! nodesA concat nodes)
                                   #_(doseq [node nodes]
                                       (put! nodesB| node))))))))
                  (reset! nodesA (drop 8 nodes-sorted)))
                (recur (timeout 1000)))))))

      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop [i 4
                 ts (js/Date.now)
                 time-total 0]
            (when (and (= i 0) (< time-total 1000))
              (<! (timeout 1000)))
            (let [[seeder port] (alts! [stop| seeders|])]
              (when seeder
                (<! (timeout 50))
                (request-metadata* seeder)
                (recur (mod (inc i) 4) (js/Date.now) (+ time-total (- (js/Date.now) ts))))))))

      (alt!
        [(timeout (* 15 1000)) cancel|]
        ([_ _]
         (release)
         nil)

        result|
        ([value]
         (release)
         value)))))

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
                   (let [self-idB (js/Buffer.from "a8fb5c14469fc7c46e91679c493160ed3d13be3d" "hex") #_(.randomBytes crypto 20)]
                     {:self-id (.toString self-idB "hex")
                      :self-idB self-idB
                      :routing-table (sorted-map)
                      :routing-table-sampled {}
                      :routing-table-find-noded {}})
                   (<! (load-state data-dir))))
          self-id (:self-id @stateA)
          self-idB (:self-idB @stateA)
          routing-table-comparator (fn [id1 id2]
                                     (distance-compare
                                      (xor-distance self-idB (js/Buffer.from id1 "hex"))
                                      (xor-distance self-idB (js/Buffer.from id2 "hex"))))
          _ (swap! stateA update :routing-table (partial into (sorted-map-by routing-table-comparator)))
          port 6881
          address "0.0.0.0"
          duration (* 10 60 1000)
          count-torrentsA (atom 0)
          count-infohashesA (atom 0)
          count-discoveryA (atom 0)
          count-messagesA (atom 0)
          started-at (t/now)
          routing-table-max-size 128

          add-nodes (fn [nodes]
                      (let [routing-table (:routing-table @stateA)]
                        (->>
                         nodes
                         (transduce
                          (comp
                           (filter (fn [node]
                                     (and (not= (:address node) address)
                                          (not= (:id node) self-id)
                                          #_(not= 0 (js/Buffer.compare (:id node) self-id))
                                          (< 0 (:port node) 65536)))))
                          (completing
                           (fn [result node]
                             (if (and
                                  #_(< (count routing-table) routing-table-max-size)
                                  (not (get routing-table (:id node))))
                               (assoc! result (:id node) node)
                               result)))
                          (transient {}))
                         (persistent!)
                         (swap! stateA update :routing-table merge))))

          nodes-to-sample| (chan (sliding-buffer 1024)
                                 (comp
                                  (filter (fn [node]
                                            (and (not= (:address node) address)
                                                 (not= (:id node) self-id)
                                                 (< 0 (:port node) 65536))))))

          _ (doseq [[id node] (take 8 (shuffle (:routing-table @stateA)))]
              (>! nodes-to-sample| node))

          nodes-bootstrap [{:address "router.bittorrent.com"
                            :port 6881}
                           {:address "dht.transmissionbt.com"
                            :port 6881}
                           #_{:address "dht.libtorrent.org"
                              :port 25401}]

          msg| (chan (sliding-buffer 100))
          msg|mult (mult msg|)
          torrent| (chan (sliding-buffer 100))
          torrent|mult (mult torrent|)
          infohash| (chan (sliding-buffer 100))
          infohash|mult (mult infohash|)
          nodesB| (chan (sliding-buffer 100))
          socket (.createSocket dgram "udp4")
          send-krpc-request (let [requestsA (atom {})
                                  msg|tap (tap msg|mult (chan (sliding-buffer 512)))]
                              (go
                                (loop []
                                  (when-let [{:keys [msg rinfo] :as value} (<! msg|tap)]
                                    (let [txn-id (some-> (. msg -t) (.toString "hex"))]
                                      (when-let [response| (get @requestsA txn-id)]
                                        (put! response| value)
                                        (close! response|)
                                        (swap! requestsA dissoc txn-id)))
                                    (recur))))
                              (fn send-krpc-request
                                ([socket msg rinfo]
                                 (send-krpc-request socket msg rinfo (timeout 2000)))
                                ([socket msg rinfo timeout|]
                                 (let [txn-id (.toString (. msg -t) "hex")
                                       response| (chan 1)]
                                   (send-krpc
                                    socket
                                    msg
                                    rinfo)
                                   (swap! requestsA assoc txn-id response|)
                                   (take! timeout| (fn [_]
                                                     (when-not (closed? response|)
                                                       (close! response|)
                                                       (swap! requestsA dissoc txn-id))))
                                   response|))))

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
      (swap! stateA merge {:torrent| (let [out| (chan (sliding-buffer 100))
                                           torrent|tap (tap torrent|mult (chan (sliding-buffer 100)))]
                                       (go
                                         (loop []
                                           (when-let [value (<! torrent|tap)]
                                             (offer! out| value)
                                             (recur))))
                                       out|)})

      (println ::self-id (:self-id @stateA))

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

      ; save state to file periodically
      (go
        (when-not (.pathExistsSync fs (.join path data-dir "state/" "find.app.bittorrent.edn"))
          (<! (save-state data-dir @stateA)))
        (loop []
          (<! (timeout (* 4.5 1000)))
          (<! (save-state data-dir @stateA))
          (recur)))


      ; trim routing-table periodically
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop []
            (alt!
              (timeout (* 4 1000))
              ([_]
               (let [nodes (:routing-table @stateA)
                     nodes-near (take (* 0.9 routing-table-max-size) nodes)
                     nodes-far (take-last
                                (- (min (count nodes) routing-table-max-size) (count nodes-near))
                                nodes)]
                 (->>
                  (concat nodes-near nodes-far)
                  (into (sorted-map-by routing-table-comparator))
                  (swap! stateA assoc :routing-table)))
               (recur))

              stop|
              (do :stop)))))

      ; print info
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop []
            (alt!

              (timeout (* 5 1000))
              ([_]
               (pprint {:count-messages @count-messagesA
                        :count-infohashes @count-infohashesA
                        :count-discovery @count-discoveryA
                        :count-torrents @count-torrentsA
                        :count-sockets @count-socketsA
                        :nodes-to-sample| (count (.-buf nodes-to-sample|))
                        :routing-table (count (:routing-table @stateA))
                        :routing-table-find-noded  (count (:routing-table-find-noded @stateA))
                        :routing-table-sampled (count (:routing-table-sampled @stateA))})
               (recur))

              stop|
              (do :stop)))))

      ; discovery
      #_(let [infohash|tap (tap infohash|mult (chan (sliding-buffer 100)))
              in-progressA (atom {})]
          (go
            (loop [timeout| (timeout 0)]
              (<! timeout|)
              (when-let [{:keys [infohashB rinfo]} (<! infohash|tap)]
                (let [infohash (.toString infohashB "hex")]
                  (when-not (get @in-progressA infohash)
                    (let [find_metadata| (find-metadata {:routing-table (:routing-table @stateA)
                                                         :socket socket
                                                         :port port
                                                         :send-krpc-request send-krpc-request
                                                         :msg|mult msg|mult
                                                         :node-idB self-idB
                                                         :infohashB infohashB
                                                         :cancel| (chan 1)})]
                      (swap! in-progressA assoc infohash find_metadata|)
                      (swap! count-discoveryA inc)
                      (take! find_metadata|
                             (fn [metadata]
                               (when metadata
                                 (put! torrent| metadata)
                                 #_(pprint (select-keys metadata ["name" :seeder-count])))
                               (swap! in-progressA dissoc infohash))))))
                (recur (timeout 500))))))

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


      ; peridiacally remove some nodes randomly 
      #_(let [stop| (chan 1)]
          (swap! procsA conj stop|)
          (go
            (loop []
              (alt!
                (timeout (* 30 1000))
                ([_]
                 (->> (:routing-table @stateA)
                      (keys)
                      (shuffle)
                      (take (* 0.1 (count (:routing-table @stateA))))
                      (apply swap! stateA update-in [:routing-table] dissoc))
                 (recur))

                stop|
                (do :stop)))))

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
        (go
          (loop [timeout| (timeout 0)]
            (alt!
              timeout|
              ([_]
               (doseq [node nodes-bootstrap]

                 (take!
                  (send-krpc-request
                   socket
                   (clj->js
                    {:t (.randomBytes crypto 4)
                     :y "q"
                     :q "find_node"
                     :a {:id self-idB
                         :target self-idB #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}})
                   (clj->js node)
                   (timeout 2000))
                  (fn [{:keys [msg rinfo] :as value}]
                    (when value
                      (when-let [nodes (goog.object/getValueByKeys msg "r" "nodes")]
                        (put! nodesB| nodes))))))
               (recur (timeout (* 3 60 1000))))

              stop|
              (do :stop)))))

      ; ping nodes and remove unresponding
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop []
            (alt!
              (timeout (* 15 1000))
              ([_]
               (doseq [[id node] (->>
                                  (:routing-table @stateA)
                                  (sequence
                                   (comp
                                    (filter (fn [[id node]]
                                              (or
                                               (not (:pinged-at node))
                                               (> (- (js/Date.now) (:pinged-at node)) (* 2 60 1000)))))
                                    (take 8))))]
                 (take! (send-krpc-request
                         socket
                         (clj->js
                          {:t (.randomBytes crypto 4)
                           :y "q"
                           :q "ping"
                           :a {:id self-idB}})
                         (clj->js node)
                         (timeout 2000))
                        (fn [value]
                          (if value
                            (swap! stateA update-in [:routing-table id] assoc :pinged-at (js/Date.now))
                            (swap! stateA update-in [:routing-table] dissoc id)))))
               (recur))

              stop|
              (do :stop)))))

      ; periodicaly ask nodes for new nodes
      (let [stop| (chan 1)]
        (swap! procsA conj stop|)
        (go
          (loop [timeout| (timeout 1000)]
            (alt!
              timeout|
              ([_]
               (doseq [[id node] (->>
                                  (sequence
                                   (comp
                                    (filter (fn [[id node]]
                                              (not (get (:routing-table-find-noded @stateA) id))))
                                    (take 4))
                                   (:routing-table @stateA)))]
                 (swap! stateA update-in [:routing-table-find-noded] assoc id {:node node
                                                                               :timestamp (js/Date.now)})
                 (take!
                  (send-krpc-request
                   socket
                   (clj->js
                    {:t (.randomBytes crypto 4)
                     :y "q"
                     :q "find_node"
                     :a {:id self-idB
                         :target self-idB #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}})
                   (clj->js node)
                   (timeout 2000))
                  (fn [{:keys [msg rinfo] :as value}]
                    (when value
                      (when-let [nodes (goog.object/getValueByKeys msg "r" "nodes")]
                        (put! nodesB| nodes))))))
               (recur (timeout (* 2 1000))))

              stop|
              (do :stop)))))

      ; sybil
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
                                      (filter (fn [[id node]] (not (get (:routing-table-find-noded @stateA) id))))
                                      (take 16))
                                     (:routing-table @stateA))
                                    (shuffle))]
                   (swap! stateA update-in [:routing-table-find-noded] assoc id {:node node
                                                                                 :timestamp (js/Date.now)})
                   (send-find-node
                    socket
                    (clj->js
                     node)
                    (gen-neighbor-id (:idB node) (:self-idB @stateA))))
                 (recur (timeout (* 5 1000))))

                stop|
                (do :stop)))
            (println :proc-sybil-exits)))

      ; ask peers directly, politely for infohashes
      #_(let [stop| (chan 1)
              nodes| (chan (sliding-buffer 1024)
                           (comp
                            (filter [node]
                                    (not (get (:routing-table-sampled @stateA) (:id node))))))
              nodes|mix (mix nodes|)]
          (swap! procsA conj stop|)
          (admix nodes|mix nodes-to-sample|)
          (go
            (loop [n 2
                   i n
                   ts (js/Date.now)
                   time-total 0]
              (when (and (= i 0) (< time-total 1000))
                (a/toggle nodes|mix {nodes-to-sample| {:pause true}})
                (<! (timeout (+ time-total (- 1000 time-total))))
                (a/toggle nodes|mix {nodes-to-sample| {:pause false}})
                (recur n n (js/Date.now) 0))
              (alt!
                nodes|
                ([node]
                 (let []
                   (swap! stateA update-in [:routing-table-sampled] assoc id (merge node
                                                                                    {:timestamp (js/Date.now)}))
                   (let [alternative-infohash-targetB (.randomBytes crypto 20)
                         txn-idB (.randomBytes crypto 4)]
                     (when-let [value (<! (send-krpc-request
                                           socket
                                           (clj->js
                                            {:t txn-idB
                                             :y "q"
                                             :q "sample_infohashes"
                                             :a {:id self-idB
                                                 :target alternative-infohash-targetB}})
                                           (clj->js node)
                                           (timeout 2000)))]
                       (let [{:keys [msg rinfo]} value
                             {:keys [interval nodes num samples]} (:r (js->clj msg :keywordize-keys true))]
                         (when samples
                           (doseq [infohashB (decode-samples samples)]
                             #_(println :info_hash (.toString infohashB "hex"))
                             (put! infohash| {:infohashB infohashB
                                              :rinfo rinfo})))
                         (when interval
                           (swap! stateA update-in [:routing-table-sampled id] merge {:interval interval}))
                         #_(when nodes
                             (put! nodes-to-sample| nodes))))))

                 (recur n (mod (inc i) n) (js/Date.now) (+ time-total (- ts (js/Date.now)))))

                stop|
                (do :stop)))))

      ; ask for infohashes, then for metadata using one tcp connection
      (let [stop| (chan 1)
            nodes| (chan 1
                         (comp
                          (filter (fn [node]
                                    (not (get (:routing-table-sampled @stateA) (:id node)))))))
            nodes|mix (mix nodes|)
            cancel-channelsA (atom [])
            release (fn []
                      (doseq [cancel| @cancel-channelsA]
                        (close! cancel|)))]
        (swap! procsA conj stop|)
        (admix nodes|mix nodes-to-sample|)
        (go
          (loop [n 8
                 i n
                 ts (js/Date.now)
                 time-total 0]
            (when (and (= i 0) (< time-total 2000))
              (a/toggle nodes|mix {nodes-to-sample| {:pause true}})
              (<! (timeout (+ time-total (- 2000 time-total))))
              (a/toggle nodes|mix {nodes-to-sample| {:pause false}})
              (recur n n (js/Date.now) 0))
            (alt!
              nodes|
              ([node]
               (let []
                 (swap! stateA update-in [:routing-table-sampled] assoc (:id node) (merge node
                                                                                          {:timestamp (js/Date.now)}))
                 (let [alternative-infohash-targetB (.randomBytes crypto 20)
                       txn-idB (.randomBytes crypto 4)]
                   #_(println :sampling-a-node)
                   (when-let [value (<! (send-krpc-request
                                         socket
                                         (clj->js
                                          {:t txn-idB
                                           :y "q"
                                           :q "sample_infohashes"
                                           :a {:id self-idB
                                               :target alternative-infohash-targetB}})
                                         (clj->js node)
                                         (timeout 2000)))]
                     (let [{:keys [msg rinfo]} value
                           {:keys [interval nodes num samples]} (:r (js->clj msg :keywordize-keys true))]
                       (when samples
                         (let [cancel| (chan 1)
                               _ (swap! cancel-channelsA conj cancel|)
                               infohashes (decode-samples samples)
                               _ (doseq [infohashB infohashes]
                                   (put! infohash| {:infohashB infohashB
                                                    :rinfo rinfo}))
                               torrents (<! (request-metadata-multiple node self-idB infohashes cancel|))]
                           (println :torrents)
                           (pprint torrents)))
                       (when interval
                         (println (:id node) interval)
                         (swap! stateA update-in [:routing-table-sampled (:id node)] merge {:interval interval}))
                       #_(when nodes
                           (put! nodes-to-sample| nodes))))))

               (recur n (mod (inc i) n) (js/Date.now) (+ time-total (- ts (js/Date.now)))))

              stop|
              (do :stop)))
          (release)))

      ; add new nodes to routing table
      (go
        (loop []
          (when-let [nodesB (<! nodesB|)]
            (let [nodes (decode-nodes nodesB)]
              (add-nodes nodes)
              (doseq [node nodes]
                (>! nodes-to-sample| node)))
            #_(println :nodes-count (count (:routing-table @stateA)))
            (recur))))

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
