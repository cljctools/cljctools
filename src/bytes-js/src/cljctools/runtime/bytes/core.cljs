(ns cljctools.runtime.bytes.core
  (:require
   [cljctools.runtime.bytes.protocols :as bytes.protocols]))

; requires js/Buffer

(declare crypto)

(when (exists? js/module)
  (defonce crypto (js/require "crypto")))

(defn bytes?
  [x]
  (instance? js/Buffer x))

(defn char-code
  [chr]
  (.charCodeAt chr 0))

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
      (let [char-int (.readUint8 buffer offset)]
        (set! offset (inc offset))
        char-int)))
  (read*
    [_ off length]
    (if (>= offset (.-length buffer))
      -1
      (let [start (+ offset off)
            end (+ start length)
            buf (.subarray buffer start end)]
        (set! offset (+ offset length))
        buf)))
  (unread* [_ char-int]
    (set! offset (dec offset)))
  bytes.protocols/Closable
  (close [_] #_(do nil)))

(defn pushback-input-stream
  [buffer]
  (TPushbackInputStream. buffer 0))

(deftype TOutputStream [arr]
  bytes.protocols/IOutputStream
  (write*
    [_ data]
    (cond
      (int? data)
      (.push arr (doto (js/Buffer.allocUnsafe 1) (.writeInt8 data)))

      (instance? js/Buffer data)
      (.push arr data)

      (string? data)
      (.push arr (js/Buffer.from data "utf8"))))
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
  [length]
  (.randomBytes crypto length))