(ns cljctools.socket.nodejs-net.core
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

(def net (js/require "net"))

(defn create-opts
  [{:as opts
    :keys [::socket.spec/host
           ::socket.spec/port
           ::socket.spec/path]}]
  {:pre [(s/assert ::socket.spec/tcp-socket-opts opts)]
   :post [(s/assert ::socket.spec/created-opts %)]}
  (let []
    {::socket.spec/connect-fn
     (fn [socket]
       (let [{:keys [::socket.spec/evt|
                     ::socket.spec/recv|]} @socket
             raw-socket (net.Socket.)]
         (doto raw-socket
           (.connect (clj->js (select-keys opts [::socket.spec/host
                                                 ::socket.spec/port
                                                 ::socket.spec/path])))
           (.on "connect" (fn []
                            (println ::connected)
                            (put! evt| {:op ::socket.spec/connected})))
           (.on "ready" (fn []
                          (println ::ready)
                          (put! evt| {:op ::socket.spec/ready})))
           (.on "timeout" (fn []
                            (println ::timeout)
                            (put! evt| {:op ::socket.spec/timeout})))
           (.on "close" (fn [code reason]
                          (println ::closed)
                          (put! evt| {:op ::socket.spec/closed
                                      ::socket.spec/reason reason
                                      ::socket.spec/code code})))
           (.on "error" (fn [error]
                          (println ::error)
                          (put! evt| {:op ::socket.spec/error
                                      ::socket.spec/error error})
                          #_(when (and (not s.connecting) (not s.pending)))))
           (.on "data" (fn [data]
                         (put! recv| data))))
         raw-socket))
     ::socket.spec/disconnect-fn
     (fn [socket]
       (let [{:keys [::socket.spec/raw-socket]} @socket]
         (.end raw-socket)))

     ::socket.spec/send-fn
     (fn [socket data]
       (let [{:keys [::socket.spec/raw-socket]} @socket]
         (.write raw-socket data)
         #_(when  (= (.readyState raw-socket) "open")
             (.write raw-socket data))))}))