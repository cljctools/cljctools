(ns cljctools.bytes.core
  (:refer-clojure :exclude [alength concat])
  (:require
   ["randombytes" :as randomBytes]
   #_["buffer/index.js" :refer [Buffer]]
   ["bitfield" :as Bitfield]
   #_["readable-stream" :refer [Readable]]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.spec :as bytes.spec]))

; requires js/Buffer

(declare crypto)

(when (exists? js/module)
  (defonce crypto (js/require "crypto")))

(defonce Buffer js/Buffer)

(defonce types
  (-> (make-hierarchy)
      (derive Buffer ::bytes.spec/byte-array)
      (derive js/String ::string)
      (derive Buffer ::bytes.spec/byte-buffer)))

(defn random-bytes
  [length]
  (randomBytes length))

(defn byte-array?
  [x]
  (instance? Buffer x))

(defmulti to-byte-array type :hierarchy #'types)

(defmethod to-byte-array ::string
  [string]
  (Buffer.from string "utf8"))

(defmethod to-byte-array ::bytes.spec/byte-buffer
  [buffer]
  buffer
  (if (== (.. buffer -buffer -byteLength) (.-length buffer))
    buffer
    (.. js/Uint8Array -prototype -slice (call buffer))))

(defn alength
  [buffer]
  (.-length buffer))

(defmulti to-string type :hierarchy #'types)

(defmethod to-string ::bytes.spec/byte-array
  [buffer]
  (.toString buffer "utf8"))

(defmethod to-string ::bytes.spec/byte-buffer
  [buffer]
  (.toString buffer "utf8"))

(defn byte-array
  [size-or-seq]
  (if (number? size-or-seq)
    (Buffer.alloc size-or-seq)
    (Buffer.from (clj->js size-or-seq))))

(defmulti concat
  (fn [xs] (type (first xs))) :hierarchy #'types)

(defmethod concat ::bytes.spec/byte-array
  [buffers]
  (Buffer.concat buffers))

(defmethod concat ::bytes.spec/byte-buffer
  [buffers]
  (Buffer.concat buffers))

(defn byte-buffer
  [size]
  (Buffer.alloc size))

(defmulti buffer-wrap (fn [x & args] (type x)) :hierarchy #'types)

(defmethod buffer-wrap ::bytes.spec/byte-buffer
  ([buffer]
   (Buffer.from (-buffer buffer) (.-byteOffset buffer) (.-length buffer)))
  ([buffer offset length]
   (Buffer.from (-buffer buffer) (+ (.-byteOffset buffer) offset) length)))

(defn get-byte
  [buffer index]
  (.readUInt8 buffer index))

(defn get-int
  [buffer index]
  (.readUInt32BE buffer index))

(defn put-int
  [buffer index value]
  (.writeUInt32BE buffer value index)
  buffer)

(defn put-short
  [buffer index value]
  (.writeUInt16BE buffer value index)
  buffer)

(defn get-short
  [buffer index]
  (.readUInt16BE buffer index))

(defn size
  [buffer]
  (.-length buffer))

(defn aset-byte
  [byte-arr idx val]
  (aset byte-arr idx val))

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

(deftype TByteArrayOutputStream [arr]
  bytes.protocols/IByteArrayOutputStream
  (write*
    [_ int8]
    (.push arr (doto (Buffer.allocUnsafe 1) (.writeInt8 int8))))
  (write-byte-array*
    [_ buffer]
    (.push arr buffer))
  (reset*
    [_]
    (.splice arr 0))
  bytes.protocols/IToByteArray
  (to-byte-array*
    [_]
    (Buffer.concat arr))
  bytes.protocols/Closable
  (close [_] #_(do nil)))

(defn byte-array-output-stream
  []
  (TByteArrayOutputStream. #js []))

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
  bytes.protocols/IToByteArray
  (to-byte-array*
    [_]
    (.-buffer bitfield)))

(defn bitset
  ([]
   (bitset 0))
  ([nbits]
   (bitset nbits {:grow (* 50000 8)}))
  ([nbits opts]
   (TBitSet. (new (.-default Bitfield) nbits (clj->js opts)))))


(defn ^{:nodejs-only true} sha1
  "takes byte array, returns byte array"
  [buffer]
  (doto (.createHash crypto "sha1")
    (.update buffer)
    (.digest)))

(comment

  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}}}' \
  -M -m cljs.main -co '{:npm-deps {"randombytes" "2.1.0"
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