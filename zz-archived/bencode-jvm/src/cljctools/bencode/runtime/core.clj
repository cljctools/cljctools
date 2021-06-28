(ns cljctools.bencode.runtime.core
  (:require
   [bencode.core])
  (:import
   (java.io ByteArrayOutputStream ByteArrayInputStream PushbackInputStream)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn encode ^bytes
  [data]
  (doto (ByteArrayOutputStream.)
    (bencode.core/write-bencode data)
    (.toByteArray)))

(defn decode
  [^bytes byte-arr]
  (-> byte-arr
      (ByteArrayInputStream.)
      (PushbackInputStream.)
      (bencode.core/read-bencode)))


