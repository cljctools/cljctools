(ns cljctools.bittorrent.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.spec :as bytes.spec]))

(s/def ::infohash string?)
(s/def ::infohashBA ::bytes.spec/byte-array)

(s/def ::peer-id string?)
(s/def ::peer-idBA ::bytes.spec/byte-array)