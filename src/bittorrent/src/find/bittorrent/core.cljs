(ns find.bittorrent.core
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
   [clojure.set]
   [tick.alpha.api :as t]
   [cognitect.transit :as transit]
   
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]))

(defonce bencode (js/require "bencode"))

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
   (flatten [values])
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

(defn hash-key-distance-comparator-fn
  [targetB]
  (fn [id1 id2]
    (distance-compare
     (xor-distance targetB (js/Buffer.from id1 "hex"))
     (xor-distance targetB (js/Buffer.from id2 "hex")))))

(defn send-krpc-request-fn
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

(defn sorted-map-buffer
  [comparator]
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
        this)
      (close-buf! [this])
      cljs.core/ICounted
      (-count [this] (count @collA)))))