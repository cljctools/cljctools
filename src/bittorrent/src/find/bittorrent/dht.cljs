(ns find.bittorrent.dht
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

   [find.bittorrent.core :refer [hash-key-distance-comparator-fn]]))

(defonce crypto (js/require "crypto"))

(defn valid-for-ping?
  [[id node]]
  (or
   (not (:pinged-at node))
   (> (- (js/Date.now) (:pinged-at node)) (* 2 60 1000))))

(defn start-routing-table
  [{:as opts
    :keys [stateA
           self-idB
           nodes|
           send-krpc-request
           socket
           routing-table-max-size]}]
  (let [_ (swap! stateA merge {:routing-table (sorted-map)})

        routing-table-comparator (hash-key-distance-comparator-fn self-idB)

        stop| (chan 1)]

    (swap! stateA update :routing-table (partial into (sorted-map-by routing-table-comparator)))

    ; add nodes to routing table
    (go
      (loop [n 4
             i 0
             ts (js/Date.now)
             time-total 0]
        (when-let [nodes (<! nodes|)]
          (let [routing-table (:routing-table @stateA)]
            (->>
             nodes
             (transduce
              (comp
               (filter (fn [node]
                         (not (get routing-table (:id node))))))
              (completing
               (fn [result node]
                 (assoc! result (:id node) node)))
              (transient {}))
             (persistent!)
             (swap! stateA update :routing-table merge))

            ; trim routing table periodically
            (if (and (>= i 4) (> time-total 4000))
              (let [nodes (:routing-table @stateA)
                    nodes-near (take (* 0.9 routing-table-max-size) nodes)
                    nodes-far (take-last
                               (- (min (count nodes) routing-table-max-size) (count nodes-near))
                               nodes)]
                (->>
                 (concat nodes-near nodes-far)
                 (into (sorted-map-by routing-table-comparator))
                 (swap! stateA assoc :routing-table))
                (recur n 0 (js/Date.now) 0))
              (recur n (inc i) (js/Date.now) (+ time-total (- (js/Date.now) ts)))))))
      (close! stop|))

    ; ping nodes and remove unresponding
    (go
      (loop []
        (alt!
          (timeout (* 15 1000))
          ([_]
           (let [state @stateA]
             (doseq [[id node] (->>
                                (:routing-table state)
                                (sequence
                                 (comp
                                  (filter valid-for-ping?)
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
                          (swap! stateA update-in [:routing-table] dissoc id))))))
           (recur))

          stop|
          (do :stop))))

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
              (do :stop)))))))


(defn start-dht-keyspace
  [{:as opts
    :keys [stateA
           self-idB
           nodes|
           send-krpc-request
           socket
           routing-table-max-size]}]
  (let [routing-table-comparator (hash-key-distance-comparator-fn self-idB)
        stop| (chan 1)]
    (swap! stateA merge {:dht-keyspace (into {}
                                             (comp
                                              (map
                                               (fn [char-str]
                                                 (->>
                                                  (repeatedly (constantly char-str))
                                                  (take 40)
                                                  (clojure.string/join ""))))
                                              (map (fn [k] [k {}])))
                                             ["0"  "2"  "4"  "6"  "8"  "a"  "c"  "e"]
                                             #_["0" "1" "2" "3" "4" "5" "6" "7" "8" "9" "a" "b" "c" "d" "e" "f"])})
    (doseq [[id routing-table] (:dht-keyspace @stateA)]
      (swap! stateA update-in [:dht-keyspace id] (partial into (sorted-map-by (hash-key-distance-comparator-fn (js/Buffer.from id "hex"))))))
    (swap! stateA update :dht-keyspace (partial into (sorted-map)))

    ; add nodes to routing table
    (go
      (loop [n 4
             i 0
             ts (js/Date.now)
             time-total 0]
        (when-let [nodes (<! nodes|)]
          (let [dht-keyspace-keys (keys (:dht-keyspace @stateA))]
            (doseq [node nodes]
              (let [closest-key (->>
                                 dht-keyspace-keys
                                 (sort-by identity (hash-key-distance-comparator-fn (:idB node)))
                                 first)]
                (swap! stateA update-in [:dht-keyspace closest-key] assoc (:id node) node)))

            ; trim routing tables periodically
            (if (and (>= i 4) (> time-total 4000))
              (do
                (swap! stateA update :dht-keyspace
                       (fn [dht-keyspace]
                         (->>
                          dht-keyspace
                          (map (fn [[id routing-table]]
                                 [id (->> routing-table
                                          (take routing-table-max-size)
                                          (into (sorted-map-by (hash-key-distance-comparator-fn (js/Buffer.from id "hex")))))]))
                          (into (sorted-map)))))
                (recur n 0 (js/Date.now) 0))
              (recur n (inc i) (js/Date.now) (+ time-total (- (js/Date.now) ts)))))))
      (close! stop|))

    ; ping nodes and remove unresponding
    (go
      (loop []
        (alt!
          (timeout (* 15 1000))
          ([_]
           (let [state @stateA]
             (doseq [[k routing-table] (:dht-keyspace state)
                     [id node] (->>
                                routing-table
                                (sequence
                                 (comp
                                  (filter valid-for-ping?)
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
                          (swap! stateA update-in [:dht-keyspace k id] assoc :pinged-at (js/Date.now))
                          (swap! stateA update-in [:dht-keyspace k] dissoc id))))))
           (recur))

          stop|
          (do :stop))))))