(ns cljctools.socket
  (:refer-clojure :exclude [send])
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   #?(:cljs [cljs.reader :refer [read-string]])
   #?(:cljs [goog.string.format])
   #?(:cljs [goog.string :refer [format]])
   [clojure.spec.alpha :as s]))

(s/def ::num-code int?)
(s/def ::reason-text string?)
(s/def ::error any?)
(s/def ::reconnection-timeout int?)

(s/def ::connected keyword?)
(s/def ::ready keyword?)
(s/def ::timeout keyword?)
(s/def ::closed keyword?)
(s/def ::error keyword?)

(defonce ^:private registryA (atom {}))

(defn start
  [{:as opts
    :keys [::id
           ::send|
           ::evt|
           ::evt|mult
           ::recv|
           ::connect-fn
           ::disconnect-fn
           ::reconnection-timeout
           ::send-fn]
    :or {id (str #?(:clj  (java.util.UUID/randomUUID)
                    :cljs (random-uuid)))
         reconnection-timeout 1000
         send| (chan (sliding-buffer 10))
         recv| (chan (sliding-buffer 10))
         evt| (chan (sliding-buffer 10))}}]
  (go
    (let [evt|mult (or evt|mult (mult evt|))
          evt|tap (tap evt|mult (chan (sliding-buffer 10)))
          stateA (atom (merge
                        opts
                        {::opts opts
                         ::send| send|
                         ::evt| evt|
                         ::evt|mult evt|mult
                         ::socket nil
                         ::recv| recv|}))
          disconnect (fn []
                       (when (get @stateA ::socket)
                         (disconnect-fn @stateA)
                         (swap! stateA dissoc ::socket)))
          connect (fn []
                    (when (get @stateA ::socket)
                      (disconnect))
                    (swap! stateA assoc ::socket (<! (connect-fn @stateA))))

          send (fn [data]
                 (when (get @stateA ::socket)
                   (send-fn @stateA data)))

          release (fn []
                    (disconnect)
                    (untap evt|mult evt|tap)
                    (close! evt|tap))]

      (swap! stateA merge  {::connect connect
                            ::disconnect disconnect
                            ::send send
                            ::release release})
      (swap! registryA assoc id stateA)
      (connect)
      (go
        (loop []
          (when-let [[value port] (alts! [send| evt|tap])]
            (condp = port

              send|
              (send value)

              evt|tap
              (condp = (:op value)

                ::closed
                (let []
                  (when reconnection-timeout
                    (<! (timeout reconnection-timeout))
                    (connect)))
                (do nil)))
            (recur)))
        (println ::go-block-exits)))))


(defn stop
  [{:keys [::id] :as opts}]
  (go
    (let [state @(get @registryA id)]
      (when (::release state)
        ((::release state)))
      (swap! registryA dissoc id))))


(defn send
  [{:keys [::id] :as opts} data]
  (go
    (let [state @(get @registryA id)]
      (when state
        ((::send state) data)))))