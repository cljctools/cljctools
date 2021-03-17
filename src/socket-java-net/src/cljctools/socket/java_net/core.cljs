(ns cljctools.socket.java-net.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols])
  (:import
   [java.net
    InetSocketAddress
    Socket
    SocketException]
   [java.io
    IOException]))

(defn create-opts
  [{:as opts
    :keys [::socket.spec/host
           ::socket.spec/port]}]
  {:pre [(s/assert ::socket.spec/tcp-socket-opts opts)]
   :post [(s/assert ::socket.spec/created-opts %)]}
  (let []
    {::socket.spec/connect-fn
     (fn [socket]
       (let [{:keys [::socket.spec/evt|
                     ::socket.spec/recv|]} @socket
             raw-socket (Socket.)]
         (doto raw-socket
           (.connect (InetSocketAddress. host port)))
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