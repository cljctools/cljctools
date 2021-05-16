(ns cljctools.runtime.bytes.core
  (:refer-clojure :exclude [bytes])
  (:require
   [cljctools.runtime.bytes.protocols :as bytes.protocols]))

; requires js/Buffer

(declare crypto)

(when (exists? js/module)
  (defonce crypto (js/require "crypto")))

(defn bytes?
  [x]
  (instance? js/Buffer x))

(defmulti to-bytes type)

(defmethod to-bytes js/String
  [string]
  (js/Buffer.from string "utf8"))

(defn size
  [buffer]
  (.-length buffer))

(defn to-string
  [buffer]
  (.toString buffer "utf8"))

(defn bytes
  [length]
  (js/Buffer.alloc length))

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
    (.push arr (doto (js/Buffer.allocUnsafe 1) (.writeInt8 int8))))
  (write-bytes*
   [_ buffer]
   (.push arr buffer))
  (reset*
    [_]
    (.splice arr 0))
  (to-bytes*
    [_]
    (js/Buffer.concat arr))
  bytes.protocols/Closable
  (close [_] #_(do nil)))

(defn output-stream
  []
  (TOutputStream. #js []))

(defn random-bytes
  {:nodejs-only true}
  [length]
  (.randomBytes crypto length))