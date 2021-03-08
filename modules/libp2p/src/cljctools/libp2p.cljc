(ns cljctools.libp2p
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljctools.libp2p.spec]))


(defonce ^:private registry* (atom {}))

(defn start-node-opts
  [{:keys [::id] :as opts}]
  {::id id
   ::channels {::foo| (chan (sliding-buffer 10))}})

(defn start-node
  [{:keys [::id] :as opts}]
  (go
    (let [procs* (atom [])
          stop-procs (fn []
                       (doseq [[stop| proc|] @procs*]
                         (close! stop|))
                       (a/merge (mapv second @procs*)))]
      (swap! registry* assoc id
             (merge
              opts
              {::stop-procs stop-procs
               ::connection nil
               ::dht nil}))

      (let [stop| (chan 1)
            proc|
            (go
              (loop []
                (when-let [[value port] (alts! [stop| foo|])]
                  (condp = port

                    stop|
                    (do nil)

                    foo|
                    (do
                      (recur)))))
              (println ::go-block-exits))]
        (swap! procs* conj [stop| proc|])))))

(defn stop-node
  [{:keys [::id] :as opts}]
  (go
    (let []
      (let [opts-in-registry (get @registry* id)]
        (when (::stop-procs opts-in-registry)
          (<! ((::stop-procs opts-in-registry))))
        (swap! registry* dissoc id)))))