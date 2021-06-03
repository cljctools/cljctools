(ns cljctools.ipfs.dht.connection
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]

   [cljctools.ipfs.proto.core :as proto.core]
   [cljctools.ipfs.spec :as ipfs.spec]))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))


(defn create
  [{:as opts
    :keys [::recv|
           ::send|
           ::msg|]}]
  (go
    (loop []
      (when-let [value (<! recv|)]


        (recur)))))