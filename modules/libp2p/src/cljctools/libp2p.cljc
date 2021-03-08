(ns cljctools.libp2p
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljctools.libp2p.spec]))


(defonce ^:private registry-ref (atom {}))

(defn start-peer-opts
  [{:keys [::id] :as opts}]
  {::id id})

(defn start-peer
  [{:keys [::id] :as opts}]
  (go
    (let []
      (swap! registry-ref assoc id
             (merge opts
                    {})))))

(defn stop-peer
  [{:keys [::id] :as opts}]
  (go
    (let []
      (swap! registry-ref dissoc id))))