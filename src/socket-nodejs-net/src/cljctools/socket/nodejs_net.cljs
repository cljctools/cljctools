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
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols]))

(def net (js/require "net"))

(s/def ::host string?)
(s/def ::port int?)
(s/def ::path string?)

(s/def ::create-opts-opts (s/or
                           :host-port
                           (s/keys :req-un [::host
                                            ::port])
                           :path
                           (s/keys :req-un [::path])))

(defn create-opts
  [{:as opts
    :keys [:host
           :port
           :path]}]
  {:pre [(s/assert ::create-opts-opts opts)]}
  (let []
    {:connect-fn
     (fn [socket]
       (let [{:keys [:evt|
                     :recv|]} @socket
             raw-socket (net.Socket.)]
         (doto raw-socket
           (.connect (clj->js (select-keys opts [:host
                                                 :port
                                                 :path])))
           (.on "connect" (fn []
                            (println ::connected)
                            (put! evt| {:op :connected})))
           (.on "ready" (fn []
                          (println ::ready)
                          (put! evt| {:op :ready})))
           (.on "timeout" (fn []
                            (println ::timeout)
                            (put! evt| {:op :timeout})))
           (.on "close" (fn [code reason]
                          (println ::closed)
                          (put! evt| {:op :closed
                                      :reason reason
                                      :code code})))
           (.on "error" (fn [error]
                          (println ::error)
                          (put! evt| {:op :error
                                      :error error})
                          #_(when (and (not s.connecting) (not s.pending)))))
           (.on "data" (fn [data]
                         (put! recv| data))))
         raw-socket))
     :disconnect-fn
     (fn [socket]
       (let [{:keys [:raw-socket]} @socket]
         (.end raw-socket)))

     :send-fn
     (fn [socket data]
       (let [{:keys [:raw-socket]} @socket]
         (.write raw-socket data)))}))