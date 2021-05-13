(ns cljctools.bittorrent.dht-crawl.lib
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
   [cljctools.bittorrent.dht-crawl.lib-impl :as lib-impl]))

(defn random-bytes
  [n]
  (lib-impl/random-bytes n))

(defn hex-decode
  [string]
  (lib-impl/hex-decode string))

(defn hex-encode-string
  [byte-data]
  (lib-impl/hex-encode-string byte-data))

(defn bencode-encode
  [data]
  (lib-impl/bencode-encode data))

(defn bencode-decode
  [data]
  (lib-impl/bencode-decode data))

(defn gen-neighbor-id
  [target-id node-id]
  (lib-impl/gen-neighbor-id target-id node-id))

(defn encode-nodes
  [nodes]
  (lib-impl/encode-nodes nodes))

(defn decode-nodes
  [nodes]
  (lib-impl/decode-nodes nodes))

(defn decode-values
  [values]
  (lib-impl/decode-values values))

(defn decode-samples
  [samples]
  (lib-impl/decode-samples values))

(defn send-krpc
  [socket msg remote-address-info]
  (lib-impl/send-krpc socket msg remote-address-info))

(defn xor-distance
  [a b]
  (lib-impl/xor-distance a b))

(defn distance-compare
  [distance1 distance2]
  (lib-impl/distance-compare distance1 distance2))

(defn hash-key-distance-comparator-fn
  [target]
  (lib-impl/hash-key-distance-comparator-fn target))

(defn send-krpc-request-fn
  [opts]
  (lib-impl/send-krpc-request-fn opts))

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
      cljs.core/ICounted
      (-count [this] (count @collA)))))

(defn transit-write
  [data]
  (lib-impl/transit-write data))

(defn transit-read
  [data]
  (lib-impl/transit-read data))

(defn write-state-file
  [filepath data]
  (lib-impl/write-state-file filepath data))

(defn read-state-file
  [filepath]
  (lib-impl/read-state-file filepath))