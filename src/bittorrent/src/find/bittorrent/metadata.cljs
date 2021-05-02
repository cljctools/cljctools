(ns find.bittorrent.metadata
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   [clojure.walk]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]

   [find.bittorrent.core :refer [hash-key-comparator-fn
                                 decode-nodes
                                 decode-values
                                 sorted-map-buffer]]))

(defonce crypto (js/require "crypto"))
(defonce net (js/require "net"))
(defonce BittorrrentProtocol (js/require "bittorrent-protocol"))
(defonce ut_metadata (js/require "ut_metadata"))
(defonce MagnetURI (js/require "magnet-uri"))
(defonce bencode (js/require "bencode"))


(def count-socketsA (atom 0))

(defn request-metadata
  [{:keys [address port]} idB infohashB cancel|]
  (go
    (let [time-out 4000
          error| (chan 1)
          result| (chan 1)
          socket (net.Socket.)
          release (fn []
                    (swap! count-socketsA dec)
                    (.destroy socket))]
      (swap! count-socketsA inc)
      (doto socket
        (.on "error" (fn [error]
                       #_(println "request-metadata-socket error" error)
                       (close! error|)))
        (.on "close" (fn [hadError]
                       (close! error|)))
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
                             #_(println :metadata (.. metadata -name (toString "utf-8")))
                             #_(pprint metadata)
                             (put! result| metadata)))))))
      (alt!

        [(timeout time-out) cancel| error|]
        ([_ _]
         (release)
         nil)

        result|
        ([value]
         (release)
         value)))))

(defn find-metadata
  [{:keys [send-krpc-request socket routing-table  msg|mult node-idB infohashB cancel|]}]
  (letfn []
    (go
      (let [seeders-countA (atom 0)
            result| (chan 1)
            nodesB| (chan (sliding-buffer 256))
            seeders| (chan 1)
            seeder| (chan 1)
            cancel-channelsA (atom [])

            nodes| (chan (sorted-map-buffer (hash-key-comparator-fn infohashB)))
            routing-table-nodes| (chan (sorted-map-buffer (hash-key-comparator-fn infohashB)
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

            _ (<! (a/onto-chan! routing-table-nodes| (sort-by first (hash-key-comparator-fn infohashB) routing-table) false))

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
                                (let [cancel| (chan 1)
                                      out| (chan 1)]
                                  (swap! cancel-channelsA conj cancel|)
                                  (take! (request-metadata node node-idB infohashB cancel|)
                                         (fn [metadata]
                                           (when metadata
                                             (let [result (merge
                                                           metadata
                                                           {:infohash (.toString infohashB "hex")
                                                            :seeder-count @seeders-countA})]
                                               (put! result| result)
                                               (put! out| result)))
                                           (close! out|)))
                                  out|))
            valid-ip? (fn [node]
                        (and (not= (:address node) "0.0.0.0")
                             (< 0 (:port node) 65536)))

            procsA (atom [])
            release (fn []
                      (doseq [stop| @procsA]
                        (close! stop|))
                      (close! nodesB|)
                      (close! seeders|)
                      (close! seeder|)
                      (close! nodes|)
                      (doseq [cancel| @cancel-channelsA]
                        (close! cancel|)))]

        (go
          (loop [n 4
                 i n
                 ts (js/Date.now)
                 time-total 0]
            (let [timeout| (when (and (= i 0) (< time-total 1000))
                             (timeout 1000))
                  [value port] (alts! (concat
                                       [seeders|]
                                       (if timeout|
                                         [timeout|]
                                         [nodes|
                                          routing-table-nodes|]))
                                      :priority true)]
              (when (or value (= port timeout|))
                (cond

                  (= port seeders|)
                  (let [seeders value]
                    (swap! seeders-countA + (count seeders))
                    (doseq [seeder seeders]
                      (>! seeder| seeder))
                    (recur n i ts time-total))

                  (= port timeout|)
                  (do
                    :cool-down
                    (recur n n (js/Date.now) 0))

                  (or (= port nodes|) (= port routing-table-nodes|))
                  (let [[id node] value]
                    (take! (send-get-peers node)
                           (fn [{:keys [token values nodes]}]
                             (cond
                               values
                               (let [seeders (->>
                                              (decode-values values)
                                              (filter valid-ip?))]
                                 (put! seeders| seeders))

                               nodes
                               (let [nodes (->>
                                            (decode-nodes nodes)
                                            (filter valid-ip?))]
                                 (a/onto-chan! nodes| (map (fn [node] [(:id node) node]) nodes) false)
                                 #_(doseq [node nodes]
                                     (put! nodesB| node))))))
                    (recur n (mod (inc i) n) (js/Date.now) (+ time-total (- (js/Date.now) ts)))))))))

        (go
          (loop [n 4
                 i n
                 batch (transient [])]
            (when (= i 0)
              (<! (a/map (constantly nil) (persistent! batch)))
              (recur n n (transient [])))
            (when-let [seeder (<! seeder|)]
              (recur n (mod (inc i) n) (conj! batch (request-metadata* seeder))))))

        (alt!
          [(timeout (* 15 1000)) cancel|]
          ([_ _]
           (release)
           nil)

          result|
          ([value]
           (release)
           value))))))

(defn start-discovery
  [{:as opts
    :keys [stateA
           self-idB
           self-id
           send-krpc-request
           socket
           infohashes-from-sampling|
           infohashes-from-listening|
           torrent|
           msg|mult

           count-discoveryA
           count-discovery-activeA]}]

  (let [in-processA (atom {})
        already-searchedA (atom #{})
        in-progress| (chan 30)]
    (go
      (loop []
        (let [[value port] (alts! [infohashes-from-listening|
                                   infohashes-from-sampling|]
                                  :priority true)]
          (when-let [{:keys [infohashB rinfo]} value]
            (let [infohash (.toString infohashB "hex")]
              (when-not (or (get @in-processA infohash)
                            (get @already-searchedA infohash))
                (>! in-progress| infohashB)
                (let [state @stateA
                      closest-key (->>
                                   (keys (:dht-keyspace state))
                                   (concat [self-id])
                                   (sort-by identity (hash-key-comparator-fn infohashB))
                                   (first))
                      closest-routing-table (if (= closest-key self-id)
                                              (:routing-table state)
                                              (get (:dht-keyspace state) closest-key))
                      find_metadata| (find-metadata {:routing-table closest-routing-table
                                                     :socket socket
                                                     :send-krpc-request send-krpc-request
                                                     :msg|mult msg|mult
                                                     :node-idB self-idB
                                                     :infohashB infohashB
                                                     :cancel| (chan 1)})]
                  (swap! in-processA assoc infohash find_metadata|)
                  (swap! already-searchedA conj infohash)
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
                           (swap! in-processA dissoc infohash))))))
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