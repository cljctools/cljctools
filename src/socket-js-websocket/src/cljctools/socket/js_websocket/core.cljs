(ns cljctools.socket.js-websocket.core
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
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols]))

(when (exists? js/module)
  (def ws (js/require "ws"))
  (set! js/WebSocket ws)
  #_(set! js/module.exports exports))


(defn create-opts
  [{:as opts
    :keys [::socket.spec/url]}]
  {:pre [(s/assert ::socket.spec/websocket-opts opts)]
   :post [(s/assert ::socket.spec/connect-opts %)]}
  (let []
    {::socket.spec/connect-fn
     (fn [socket]
       (let [{:keys [:evt|
                     :recv|]} @socket
             raw-socket (WebSocket. url #js {})]
         (doto raw-socket
           (.on "open" (fn []
                         (println ::connected)
                         (put! evt| {:op ::socket.spec/connected})))
           (.on "close" (fn [code reason]
                          (println ::closed)
                          (put! evt| {:op ::socket.spec/closed
                                      ::socket.spec/reason reason
                                      ::socket.spec/code code})))
           (.on "error" (fn [error]
                          (println ::error)
                          (put! evt| {:op ::socket.spec/error
                                      ::socket.spec/error error})))
           (.on "message" (fn [data]
                            (put! recv| data))))
         raw-socket))

     ::socket.spec/disconnect-fn
     (fn [socket]
       (let [{:keys [::socket.spec/raw-socket]} @socket]
         (.close raw-socket 1000 (str ::socket.spec/disconnected))))

     ::socket.spec/send-fn
     (fn [socket]
       (let [{:keys [::socket.spec/raw-socket]} @socket]
         (.send raw-socket data)))}))