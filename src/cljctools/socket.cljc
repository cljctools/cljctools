(ns cljctools.socket
  (:require
   (:refer-clojure :exclude [send])
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

(defonce ^:private registry* (atom {}))

(defn start
  [{:as opts
    :keys [::id
           ::send|
           ::evt|
           ::evt|mult
           ::recv|
           ::connect-fn
           ::disconnect-fn
           ::send-fn]
    :or {send| (chan (sliding-buffer 10))
         recv| (chan (sliding-buffer 10))
         evt| (chan (sliding-buffer 10))
         evt|mult (mult evt|)}}]
  (go
    (let [evt|tap (tap evt|mult (chan (sliding-buffer 10)))

          state* (atom (merge
                        opts
                        {::opts opts
                         ::send| send|
                         ::evt| evt|
                         ::evt|mult evt|mult
                         ::socket nil
                         ::recv| recv|
                         ::connect}))
          disconnect (fn []
                       (when (get @state ::socket)
                         (disconnect-fn @state*)
                         (swap! state* dissoc ::socket)))
          connect (fn []
                    (when (get @state ::socket)
                      (disconnect))
                    (swap! state* assoc ::socket (!< (connect-fn @state*))))

          send (fn [data]
                 (when (get @state ::socket)
                   (send-fn @state* data)))

          release (fn []
                    (disconnect)
                    (untap evt|mult evt|tap)
                    (close! evt|tap))]

      (swap! state* merge  {::connect connect
                            ::disconnect disconnect
                            ::send send
                            ::release release})
      (swap! registry* assoc id state*)
      (connect)
      (go
        (loop []
          (when-let [[value port] (alts! [evt|tap])]
            (condp = port

              evt|tap
              (do
                (condp = (:op value)

                  ::closed
                  (let []
                    (when reconnection-timeout
                      (<! (timeout reconnection-timeout))
                      (connect)))
                  (do nil))
                (recur)))))
        (println ::go-block-exits)))))


(defn stop
  [{:keys [::id] :as opts}]
  (go
    (let [state @(get @registry* id)]
      (when (::release state)
        ((::release state)))
      (swap! registry* dissoc id))))


(defn send
  [{:keys [::id] :as opts} data]
  (go
    (let [state @(get @registry* id)]
      (when state
        ((::send state) data)))))