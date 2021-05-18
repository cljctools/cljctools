(ns cljctools.bytes.core
  (:refer-clojure :exclude [bytes])
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   ["randombytes" :as randomBytes]
   ["safe-buffer" :refer [Buffer]]
   ["bitfield" :as Bitfield]))

#_(declare crypto)

#_(when (exists? js/module)
    (defonce crypto (js/require "crypto")))

(defn bytes?
  [x]
  (instance? Buffer x))

(defmulti to-bytes type)

(defmethod to-bytes js/String
  [string]
  (Buffer.from string "utf8"))

(defn size
  [buffer]
  (.-length buffer))

(defn to-string
  [buffer]
  (.toString buffer "utf8"))

(defn bytes
  [size-or-seq]
  (if (number? size-or-seq)
    (Buffer.alloc size-or-seq)
    (Buffer.from size-or-seq)))

(deftype TPushbackInputStream [buffer ^:mutable offset]
  bytes.protocols/IPushbackInputStream
  (read*
    [_]
    (if (>= offset (.-length buffer))
      -1
      (let [int8 (.readUint8 buffer offset)]
        (set! offset (inc offset))
        int8)))
  (read*
    [_ off length]
    (if (>= offset (.-length buffer))
      -1
      (let [start (+ offset off)
            end (+ start length)
            buf (.subarray buffer start end)]
        (set! offset (+ offset length))
        buf)))
  (unread* [_ int8]
    (set! offset (dec offset)))
  bytes.protocols/Closable
  (close [_] #_(do nil)))

(defn pushback-input-stream
  [buffer]
  (TPushbackInputStream. buffer 0))

(deftype TOutputStream [arr]
  bytes.protocols/IOutputStream
  (write*
    [_ int8]
    (.push arr (doto (Buffer.allocUnsafe 1) (.writeInt8 int8))))
  (write-bytes*
    [_ buffer]
    (.push arr buffer))
  (reset*
    [_]
    (.splice arr 0))
  bytes.protocols/IToBytes
  (to-bytes*
    [_]
    (Buffer.concat arr))
  bytes.protocols/Closable
  (close [_] #_(do nil)))

(defn output-stream
  []
  (TOutputStream. #js []))

(defn random-bytes
  [length]
  (randomBytes length))

(deftype TBitSet [bitfield]
  bytes.protocols/IBitSet
  (get*
    [_ bit-index]
    (.get bitfield bit-index))
  (get-subset*
    [_ from-index to-index]
    (TBitSet. (new (.-default Bitfield)
               (.slice (.-buffer bitfield) from-index to-index)
               #js {:grow (* 50000 8)})))
  (set*
    [_ bit-index]
    (.set bitfield bit-index))
  (set*
    [_ bit-index value]
    (.set bitfield  bit-index ^boolean value))
  bytes.protocols/IToBytes
  (to-bytes*
    [_]
    (.-buffer bitfield))
  bytes.protocols/IToArray
  (to-array*
   [_]
   (js/Array.from (.-buffer bitfield))))

(defn bitset
  ([]
   (bitset 0))
  ([nbits]
   (bitset nbits {:grow (* 50000 8)}))
  ([nbits opts]
   (TBitSet. (new (.-default Bitfield) nbits (clj->js opts)))))

(comment

  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}}}' \
  -M -m cljs.main -co '{:npm-deps {"randombytes" "2.1.0"
                                   "safe-buffer" "5.2.1"
                                   "bitfield" "4.0.0"}
                        :install-deps true}' \
  --repl-env node --compile cljctools.bytes.core --repl
  
  (require '[cljctools.bytes.core :as bytes.core] :reload)
  
  (bytes.core/random-bytes 20)
  
  (in-ns 'cljctools.bytes.core)

  (= Buffer js/Buffer)
  
  (do
    (def b (bitset 0))
    (bytes.protocols/set* b 2)
    (println (bytes.protocols/to-array* b))

    (bytes.protocols/set* b 3)
    (println (bytes.protocols/to-array* b)))

  ;
  )