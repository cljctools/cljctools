(ns cljctools.socket.nodejs-net
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   [cljs.reader :refer [read-string]]
   [goog.string.format]
   [goog.string :refer [format]]
   [clojure.spec.alpha :as s]
   [cljctools.socket]))


(s/def ::host string?)
(s/def ::port int?)
(s/def ::path string?)

(def net (js/require "net"))

(defn create-opts
  [{:keys [::host
           ::port
           ::path] :as opts}]
  (let []
    {::cljctools.socket/connect-fn
     (fn [{:keys [::cljctools.socket/evt|
                  ::cljctools.socket/recv|]}]
       (let [socket (net.Socket.)]
         (doto socket
           (.connect (clj->js (select-keys opts [::host
                                                 ::port
                                                 ::path])))
           (.on "connect" (fn []
                            (println ::connected)
                            (put! evt| {:op ::cljctools.socket/connected})))
           (.on "ready" (fn []
                          (println ::ready)
                          (put! evt| {:op ::cljctools.socket/ready})))
           (.on "timeout" (fn []
                            (println ::timeout)
                            (put! evt| {:op ::cljctools.socket/timeout})))
           (.on "close" (fn [code reason]
                          (println ::closed)
                          (put! evt| {:op ::cljctools.socket/closed
                                      ::cljctools.socket/reason reason
                                      ::cljctools.socket/code code})))
           (.on "error" (fn [error]
                          (println ::error)
                          (put! evt| {:op ::cljctools.socket/error
                                      ::cljctools.socket/error error})
                          #_(when-let [s @socket]
                              (when (and (not s.connecting) (not s.pending))
                                (socket.chan/op
                                 {::op.spec/op-key ::socket.chan/error}
                                 (::socket.chan/evt| channels)
                                 error)))))
           (.on "data" (fn [data]
                         (put! recv| data))))
         socket))
     ::cljctools.socket/disconnect-fn
     (fn [{:keys [::cljctools.socket/socket]}]
       (.end socket))

     ::cljctools.socket/send-fn
     (fn [{:keys [::cljctools.socket/socket]} data]
       (.write socket data))}))