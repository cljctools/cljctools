(ns cljctools.socket.websocket
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

(s/def ::url string?)

(when (exists? js/module)
  (def ws (js/require "ws"))
  (set! js/WebSocket ws)
  #_(set! js/module.exports exports))

(defn create-opts
  [{:keys [::url] :as opts}]
  (let []
    {::cljctools.socket/connect-fn
     (fn [socket]
       (let [{:keys [::cljctools.socket/evt|
                     ::cljctools.socket/recv|]} @socket
             raw-socket (WebSocket. url #js {})]
         (doto raw-socket
           (.on "open" (fn []
                         (println ::connected)
                         (put! evt| {:op ::cljctools.socket/connected})))
           (.on "close" (fn [code reason]
                          (println ::closed)
                          (put! evt| {:op ::cljctools.socket/closed
                                      ::cljctools.socket/reason reason
                                      ::cljctools.socket/code code})))
           (.on "error" (fn [error]
                          (println ::error)
                          (put! evt| {:op ::cljctools.socket/error
                                      ::cljctools.socket/error error})))
           (.on "message" (fn [data]
                            (put! recv| data))))
         raw-socket))

     ::cljctools.socket/disconnect-fn
     (fn [socket]
       (let [{:keys [::cljctools.socket/raw-socket]} @socket]
         (.close raw-socket 1000 (str ::cljctools.socket/disconnected))))

     ::cljctools.socket/send-fn
     (fn [socket]
       (let [{:keys [::cljctools.socket/raw-socket]} @socket]
         (.send raw-socket data)))}))