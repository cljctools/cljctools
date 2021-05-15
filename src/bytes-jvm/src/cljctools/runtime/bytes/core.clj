(ns cljctools.runtime.bytes
  (:refer-clojure :exclude [bytes? bytes])
  (:require
   [cljctools.runtime.bytes.protocols :as bytes.protocols])
  (:import
   (java.io ByteArrayOutputStream ByteArrayInputStream PushbackInputStream Closable)))

(set! *warn-on-reflection* true)

(defn bytes?
  [x]
  (clojure.core/bytes? x))

(defn char-code
  [^Character chr]
  (int chr))

(defmulti to-bytes type)

(defmethod to-bytes String
  [^String string]
  (.getBytes string "UTF-8"))

(defn size
  [^bytes bytes-arr]
  (alength bytes-arr))

(defn to-string
  [^bytes bytes-arr]
  (String. bytes-arr "UTF-8"))

(defn bytes
  [^Number length]
  (byte-array length))

(deftype TPushbackInputStream [^PushbackInputStream in]
  bytes.protocols/IPushbackInputStream
  (read*
    [_]
    (.read in))
  (read*
   [_ ^Number offset ^Number length]
   (let [^bytes byte-arr (byte-array length)]
     (.read in byte-arr offset length)
     byte-arr))
  (unread*
   [_ ^Number char-int]
   (.unread in))
  java.io.Closable
  (close [_] #_(do nil)))

(defn pushback-input-stream
  [^bytes byte-arr]
  (->
   byte-arr
   (ByteArrayInputStream.)
   (PushbackInputStream.)
   (TPushbackInputStream.)))

(deftype TOutputStream [^ByteArrayOutputStream out]
  bytes.protocols/IOutputStream
  (write*
    [_ data]
    (.write out data))
  (reset*
    [_]
    (.reset out))
  (to-buffer*
    [_]
    (.toByteArray out))
  java.io.Closable
  (close [_] #_(do nil)))

(defn output-stream
  []
  (->
   (ByteArrayOutputStream.)
   (TOutputStream.)))

