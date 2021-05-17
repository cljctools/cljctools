(ns cljctools.datagram-socket.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.spec.alpha :as s]

   [cljctools.datagram-socket.spec :as datagram-socket.spec]
   [cljctools.datagram-socket.protocols :as datagram-socket.protocols]
   [manifold.deferred :as d]
   [manifold.stream :as sm]
   [aleph.udp])
  (:import
   (java.net InetSocketAddress)
   (io.netty.bootstrap Bootstrap)
   (io.netty.channel ChannelPipeline)))

(set! *warn-on-reflection* true)

(s/def ::opts (s/keys :req [::datagram-socket.spec/host
                            ::datagram-socket.spec/port
                            ::datagram-socket.spec/on-listening
                            ::datagram-socket.spec/on-message
                            ::datagram-socket.spec/on-error]
                      :opt []))

(defn create
  [{:as opts
    :keys [::datagram-socket.spec/host
           ::datagram-socket.spec/port
           ::datagram-socket.spec/on-listening
           ::datagram-socket.spec/on-message
           ::datagram-socket.spec/on-error]
    :or {host "0.0.0.0"
         port 6881}}]
  {:pre [(s/assert ::opts opts)]
   :post [(s/assert ::datagram-socket.spec/socket %)]}
  (let [stateA (atom {})
        streamV (volatile! nil)

        ^{:type ::datagram-socket.spec/socket}
        (reify
          datagram-socket.protocols/Socket
          (listen*
            [_]
            (try
              (let [stream @(aleph.udp/socket {:socket-address (InetSocketAddress. ^String host ^int port)
                                               :insecure? true})]
                (vreset! streamV stream)
                (on-listening)
                (d/loop []
                  (->
                   (sm/take! stream ::none)
                   (d/chain
                    (fn [msg]
                      (when-not (identical? msg ::none)
                        (on-message (:message msg) (select-keys msg [:host :port]))
                        (d/recur)))))))
              (catch Exception ex
                (on-error ex))))
          (send*
            [_ byte-arr {:keys [host port]}]
            (sm/put! @streamV {:host host
                               :port port
                               :message byte-arr}))
          (close*
            [_]
            (sm/close! @streamV))
          clojure.lang.IDeref
          (deref [_] @stateA))]

    (reset! stateA {:opts opts
                    :streamV streamV})
    socket))


(comment
  
  
  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/datagram-socket-jvm {:local/root "./cljctools/src/datagram-socket-jvm"}}}'
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    (require '[cljctools.datagram-socket.core :as datagram-socket.core]))
  
  (do
    (def c| (chan 10))
    (go (loop []
          (when-let [v (<! c|)]
            (println :c v)
            (recur))))
    (->
     (a/thread
       (try
         (loop [i 3]
           (when (= i 0)
             (throw (ex-info "error" {})))
           (Thread/sleep 1000)
           (println i)
           (put! c| i)
           (recur (dec i)))
         (catch Exception e
           :foo)))
     (take! prn)))
  
  ;
  )