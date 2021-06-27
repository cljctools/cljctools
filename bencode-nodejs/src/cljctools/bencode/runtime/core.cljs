(ns cljctools.bencode.runtime.core)

(def bencode (js/require "bencode"))

(defn encode
  [data]
  (.encode bencode (clj->js data)))

(defn decode
  [byte-arr]
  (.decode bencode byte-arr))


