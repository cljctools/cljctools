(ns cljctools.transit.impl
  (:require
   [clojure.string]
   [cognitect.transit :as transit]
   [cljctools.bytes.impl :as bytes.impl]))
 
 (defn write-to-string
   [data type-kw opts]
   (let [writer (transit/writer type-kw opts)]
     (transit/write writer data)))

(defn write-to-byte-array
  [data type-kw opts]
  (->
   (write-to-string data type-kw opts)
   (bytes.impl/to-byte-array)))

(defn read-string
  [string type-kw opts]
  (let [reader (transit/reader type-kw opts)]
    (transit/read reader string)))

(defn read-byte-array
  [buffer type-kw opts]
  (->
   (bytes.impl/to-string buffer)
   (read-string type-kw opts)))
