(ns cljctools.bittorrent.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.spec :as bytes.spec]))

(s/def ::infohash string?)
(s/def ::infohashB ::bytes.spec/bytes)

(s/def ::peer-id string?)
(s/def ::peer-idB ::bytes.spec/bytes)