(ns cljctools.transit.impl.core
  (:require
   [clojure.string]
   [cognitect.transit :as transit]
   [cljctools.bytes.impl.core :as bytes.impl.core]))
 
 (defn write-to-string
   [data type-kw opts]
   (let [writer (transit/writer type-kw opts)]
     (transit/write writer data)))

(defn write-to-byte-array
  [data type-kw opts]
  (->
   (write-to-string data type-kw opts)
   (bytes.impl.core/to-byte-array)))

(defn read-string
  [string type-kw opts]
  (let [reader (transit/reader type-kw opts)]
    (transit/read reader string)))

(defn read-byte-array
  [buffer type-kw opts]
  (->
   (bytes.impl.core/to-string buffer)
   (read-string type-kw opts)))
