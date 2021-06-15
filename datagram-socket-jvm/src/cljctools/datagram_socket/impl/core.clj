(ns cljctools.datagram-socket.impl.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.spec.alpha :as s]

   [cljctools.bytes.impl.core :as bytes.impl.core]
   [cljctools.datagram-socket.spec :as datagram-socket.spec]
   [cljctools.datagram-socket.protocols :as datagram-socket.protocols]
   [manifold.deferred :as d]
   [manifold.stream :as sm]
   [aleph.udp])
  (:import
   (java.net InetSocketAddress InetAddress)
   (io.netty.bootstrap Bootstrap)
   (io.netty.channel ChannelPipeline)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(s/def ::opts (s/keys :req [::datagram-socket.spec/host
                            ::datagram-socket.spec/port
                            ::datagram-socket.spec/evt|
                            ::datagram-socket.spec/msg|
                            ::datagram-socket.spec/ex|]
                      :opt []))

(defn create
  [{:as opts
    :keys [::datagram-socket.spec/host
           ::datagram-socket.spec/port
           ::datagram-socket.spec/evt|
           ::datagram-socket.spec/msg|
           ::datagram-socket.spec/ex|]
    :or {host "0.0.0.0"
         port 6881}}]
  {:pre [(s/assert ::opts opts)]
   :post [(s/assert ::datagram-socket.spec/socket %)]}
  (let [streamV (volatile! nil)
        
        socket
        ^{:type ::datagram-socket.spec/socket}
        (reify
          datagram-socket.protocols/Socket
          (listen*
            [t]
            (->
             (d/chain
              (aleph.udp/socket {:socket-address (InetSocketAddress. ^String host ^int port)
                                 :insecure? true})
              (fn [stream]
                (vreset! streamV stream)
                (put! evt| {:op :listening})
                stream)
              (fn [stream]
                (d/loop []
                  (->
                   (sm/take! stream ::none)
                   (d/chain
                    (fn [msg]
                      (when-not (identical? msg ::none)
                        (let [^InetSocketAddress inet-socket-address (:sender msg)]
                          #_[^InetAddress inet-address (.getAddress inet-socket-address)]
                          #_(.getHostAddress inet-address)
                          (put! msg| {:msgBA (:message msg)
                                      :host (.getHostString inet-socket-address)
                                      :port (.getPort inet-socket-address)}))
                        (d/recur))))
                   (d/catch Exception (fn [ex]
                                        (put! ex| ex)
                                        (datagram-socket.protocols/close* t)))))))
             (d/catch Exception (fn [ex]
                                  (put! ex| ex)
                                  (datagram-socket.protocols/close* t)))))
          (send*
            [_ byte-arr {:keys [host port]}]
            (sm/put! @streamV {:host host
                               :port port
                               :message byte-arr}))
          (close*
            [_]
            (when-let [stream @streamV]
              (sm/close! stream)))
          clojure.lang.IDeref
          (deref [_] @streamV))]

    socket))


(comment
  
  
  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/bytes-jvm"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/bytes-meta"}
                      github.cljctools/datagram-socket-jvm {:local/root "./cljctools/datagram-socket-jvm"}}}'
  
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