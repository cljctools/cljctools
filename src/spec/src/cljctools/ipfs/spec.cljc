(ns cljctools.ipfs.spec
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::multiaddress string?)