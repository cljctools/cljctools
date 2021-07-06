(ns cljctools.ipfs.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.ipfs.protocols :as ipfs.protocols]))

(s/def ::dht #(and
               (satisfies? ipfs.protocols/Dht %)))