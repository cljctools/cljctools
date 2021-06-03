(ns cljctools.ipfs.proto.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]

   [protojure.protobuf]
   [cljctools.ipfs.spec :as ipfs.spec]
   [cljctools.ipfs.proto.dht :as proto.dht]))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(defn foo-to-bar
  [])