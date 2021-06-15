(ns cljctools.ipfs.dht.wire
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]

   [cljctools.bytes.impl.core :as bytes.impl.core]
   [cljctools.ipfs.impl.core :refer []]
   [cljctools.ipfs.spec :as ipfs.spec]))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(def multistreamBA (bytes.impl.core/to-byte-array "/multistream/1.0.0\n"))
(def not-availableBA (bytes.impl.core/to-byte-array "na\n"))
(def newlineBA (bytes.impl.core/to-byte-array "\n"))
(def noiseBA (bytes.impl.core/to-byte-array "/noise"))
(def mplexBA (bytes.impl.core/to-byte-array "/mplex/1.0.0"))

(defn create
  [{:as opts
    :keys [::recv|
           ::send|
           ::msg|]}]
  (go
    (loop []
      (when-let [msgBB (<! recv|)]
        

        (recur)))))