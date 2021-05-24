(ns cljctools.bittorrent.dht-crawl.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cljctools.transit :as transit.core]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.codec.core :as codec.core]
   [cljctools.fs.core :as fs.core]))

(defn gen-neighbor-id
  [target-idBA node-idBA]
  (->>
   [(bytes.core/buffer-wrap target-idBA  0 10)
    (bytes.core/buffer-wrap node-idBA  10 (- (bytes.core/alength node-idBA) 10))]
   (bytes.core/concat)
   (bytes.core/to-byte-array)))

(defn encode-nodes
  [nodes]
  (->> nodes
       (map (fn [[id node]]
              (->>
               [(:idBA node)
                (->>
                 (clojure.string/split (:address node) ".")
                 (map #?(:clj #(Integer/parseInt %) :cljs js/Number.parseInt))
                 (bytes.core/byte-array))
                (doto (bytes.core/byte-buffer 2)
                  (bytes.core/put-short 0 (:port node))
                  (bytes.core/to-byte-array))]
               (bytes.core/concat))))
       (bytes.core/concat)))

(defn decode-nodes
  [nodesBA]
  (try
    (let [nodesBB (bytes.core/buffer-wrap nodesBA)]
      (for [i (range 0 (bytes.core/alength nodesBA) 26)]
        (let [idBA (-> nodesBB (bytes.core/buffer-wrap i 20) (bytes.core/to-byte-array))]
          {:id (codec.core/hex-encode-string idBA)
           :idBA idBA
           :address (str (bytes.core/get-byte nodesBB (+ i 20)) "."
                         (bytes.core/get-byte nodesBB (+ i 21)) "."
                         (bytes.core/get-byte nodesBB (+ i 22)) "."
                         (bytes.core/get-byte nodesBB (+ i 23)))
           :port (bytes.core/get-short nodesBB (+ i 24))})))
    (catch (:clj Exception :cljs :default) ex nil)))


(defn decode-values
  [values]
  (->>
   (flatten [values])
   (sequence
    (comp
     (filter (fn [x] (bytes.core/byte-array? x)))
     (map
      (fn [peer-infoBA]
        {:address (str (aget peer-infoBA 0) "."
                       (aget peer-infoBA 1) "."
                       (aget peer-infoBA 2) "."
                       (aget peer-infoBA 3))
         :port (->
                (bytes.core/buffer-wrap  peer-infoBA)
                (bytes.core/get-short 4))}))))))

(defn decode-samples
  [samplesBA]
  (let [samplesBB (bytes.core/buffer-wrap samplesBA)]
    (for [i (range 0 (bytes.core/alength samplesBA) 20)]
      (->
       (bytes.core/buffer-wrap samplesBA i 20)
       (bytes.core/to-byte-array)))))

(defn xor-distance
  [xBA yBA]
  (let [xBA-length (bytes.core/alength xBA)]
    (when-not (== xBA-length (bytes.core/alength yBA))
      (throw (ex-info "xor-distance: args should have same length" {})))
    (reduce
     (fn [resultBA i]
       (bytes.core/aset-byte resultBA i (bit-xor (aget xBA i) (aget yBA i)))
       resultBA)
     (bytes.core/byte-array xBA-length)
     (range 0 xBA-length))))

(defn distance-compare
  [distance1BA distance2BA]
  (let [distance1BA-length (bytes.core/alength distance1BA-length)]
    (when-not (== distance1BA-length (bytes.core/alength distance2BA))
      (throw (ex-info "distance-compare: buffers should have same length" {})))
    (reduce
     (fn [result i]
       (let [a (aget distance1BA i)
             b (aget distance2BA i)]
         (cond
           (== a b) 0
           (< a b) (reduced -1)
           (> a b) (reduced 1))))
     0
     (range 0 distance1BA-length))))

(defn hash-key-distance-comparator-fn
  [targetBA]
  (fn [id1 id2]
    (distance-compare
     (xor-distance targetBA (codec.core/hex-decode id1))
     (xor-distance targetBA (codec.core/hex-decode id2)))))

(defn sorted-map-buffer
  "sliding according to comparator sorted-map buffer"
  [n comparator]
  (let [collA (atom (sorted-map-by comparator))]
    (reify
      clojure.core.async.impl.protocols/UnblockingBuffer
      clojure.core.async.impl.protocols/Buffer
      (full? [this] false)
      (remove! [this]
        (let [[id node :as item] (first @collA)]
          (swap! collA dissoc id)
          item))
      (add!* [this [id node]]
        (swap! collA assoc id node)
        (when (> (count @collA) n)
          (swap! collA dissoc (key (last @collA))))
        this)
      (close-buf! [this])
      #?@(:clj
          [clojure.lang.Counted
           (count [this] (count @collA))]
          :cljs
          [cljs.core/ICounted
           (-count [this] (count @collA))]))))


(def transit-write
  (let [handlers #?(:clj {bytes.core/ByteArray
                          (transit/write-handler
                           (fn [byte-arr] "::bytes.core/byte-array")
                           (fn [byte-arr] (codec.core/hex-encode-string byte-arr)))
                          clojure.core.async.impl.channels.ManyToManyChannel
                          (transit/write-handler
                           (fn [c|] "ManyToManyChannel")
                           (fn [c|] nil))}
                    :cljs {bytes.core/Buffer
                           (transit/write-handler
                            (fn [buffer] "::bytes.core/byte-array")
                            (fn [buffer] (codec.core/hex-encode-string buffer)))
                           cljs.core.async.impl.channels/ManyToManyChannel
                           (transit/write-handler
                            (fn [c|] "ManyToManyChannel")
                            (fn [c|] nil))})]
    (fn [data]
      (transit.core/write-to-string data :json {:handlers handlers}))))

(def transit-read
  (let [handlers #?(:clj {"::bytes.core/byte-array"
                          (fn [string] (codec.core/hex-decode string))
                          "ManyToManyChannel"
                          (fn [string] nil)}
                    :cljs {"::bytes.core/byte-array"
                           (fn [string] (codec.core/hex-decode string))
                           "ManyToManyChannel"
                           (fn [string] nil)})]
    (fn [data-string]
      (transit.core/read-string data-string :json {:handlers handlers}))))

(defn read-state-file
  [filepath]
  (go
    (try
      (when (fs.core/path-exists? filepath)
        (let [data-string (bytes.core/to-string (fs.core/read-file filepath))]
          (transit-read data-string)))
      (catch (:clj Exception :cljs :default) ex (println ::read-state-file ex)))))

(defn write-state-file
  [filepath data]
  (go
    (try
      (let [data-string (transit-write data)]
        (fs.core/make-parents filepath)
        (fs.core/write-file filepath data-string))
      (catch (:clj Exception :cljs :default) ex (println ::write-state-file ex)))))

#_(defn send-krpc
  [socket msg rinfo]
  (let [msgB (.encode bencode msg)]
    (.send socket msgB 0 (.-length msgB) (. rinfo -port) (. rinfo -address))))

#_(defn send-krpc-request-fn
    [{:as opts
      :keys [msg|mult]}]
    (let [requestsA (atom {})
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
           response|)))))


(comment

  (do
    (defn hash-string
      [letter]
      (clojure.string/join "" (take 40 (repeatedly (constantly letter)))))

    (def targetB (js/Buffer.from (hash-string "5")  "hex"))

    (def sm (sorted-map-by (hash-key-distance-comparator-fn targetB)))

    (def sm (->
             (reduce
              (fn [result letter]
                (assoc result (hash-string letter) letter))
              (sorted-map-by (hash-key-distance-comparator-fn targetB))
              (shuffle ["0" "1" "2" "3" "4" "5" "6" "7" "8" "9" "a" "b" "c" "d" "e" "f"]))
             (assoc (hash-string "2") "2")))



    (println (take 16 (vals sm))))

  ;
  )

(comment

  (.-length (js/Buffer.from (hash-string "5")   "hex"))

  (def targetB (js/Buffer.from (hash-string "5")  "hex"))

  (.toString (xor-distance targetB (js/Buffer.from (hash-string "4")  "hex")) "hex")
  (.toString (xor-distance targetB (js/Buffer.from (hash-string "c")  "hex")) "hex")
  (.toString (xor-distance targetB (js/Buffer.from (hash-string "5")  "hex")) "hex")
  (.toString (xor-distance targetB (js/Buffer.from (hash-string "d")  "hex")) "hex")

  (js/Array.from (js/Buffer.from (hash-string "6")  "hex"))
  (js/Array.from (js/Buffer.from (hash-string "5")  "hex"))
  (js/Array.from (js/Buffer.from (hash-string "c")  "hex"))

  (js/Array.from (js/Buffer.from (hash-string "8")  "hex"))

  ;
  )

(comment

  (extend-protocol IPrintWithWriter
    js/Buffer
    (-pr-writer [buffer writer _]
      (write-all writer "#js/buffer \"" (.toString buffer) "\"")))

  (cljs.reader/register-tag-parser!
   'js/buffer
   (fn [value]
     (js/Buffer.from value)))

  (cljs.reader/read-string

   "#js/buffer \"96190f486de62449099f9caf852964b2e12058dd\"")

  (println (cljs.reader/read-string {:readers {'foo identity}} "#foo :asdf"))

  ;
  )


