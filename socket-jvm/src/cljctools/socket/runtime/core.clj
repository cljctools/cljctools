(ns cljctools.socket.runtime.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.spec.alpha :as s]

   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols]

   [manifold.deferred :as d]
   [manifold.stream :as sm]
   [aleph.tcp])
  (:import
   (java.net InetSocketAddress)
   (io.netty.bootstrap Bootstrap)
   (io.netty.channel ChannelPipeline)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(s/def ::ssl-handler #(instance? io.netty.handler.ssl.SslHandler %))

(s/def ::socket-stream #(instance? manifold.stream.SplicedStream %))

(s/def ::opts (s/keys :req [::socket.spec/evt|
                            ::socket.spec/msg|
                            ::socket.spec/ex|]
                      :opt [::socket.spec/port
                            ::socket.spec/host
                            ::socket-stream
                            ::ssl-handler]))

(defn create
  [{:as opts
    :keys [::socket.spec/port
           ::socket.spec/host
           ::socket-stream
           ::socket.spec/evt|
           ::socket.spec/msg|
           ::socket.spec/ex|]}]
  {:pre [(s/assert ::opts opts)]
   :post [(s/assert ::socket.spec/socket %)]}
  (let [streamV (volatile! nil)
        socket
        ^{:type ::socket.spec/socket}
        (reify
          socket.protocols/Socket
          (connect*
            [t]
            (->
             (d/chain
              (if socket-stream
                socket-stream
                (aleph.tcp/client (merge
                                   {:host host
                                    :port port
                                    :insecure? true}
                                   opts)))
              (fn [stream]
                (vreset! streamV stream)
                (put! evt| {:op :connected})
                stream)
              (fn [stream]
                (d/loop []
                  (->
                   (sm/take! stream nil)
                   (d/chain
                    (fn [byte-arr]
                      (if byte-arr
                        (do
                          (put! msg| byte-arr)
                          (d/recur))
                        (do
                          (when @streamV
                            (throw (ex-info (str ::socket-stream-closed) {} nil)))))))
                   (d/catch Exception (fn [ex]
                                        (put! ex| ex)
                                        (socket.protocols/close* t)))))))
             (d/catch Exception (fn [ex]
                                  (put! ex| ex)
                                  (socket.protocols/close* t)))))
          (send*
            [_ byte-arr]
            (sm/put! @streamV byte-arr))
          socket.protocols/Close
          (close*
            [_]
            (when-let [stream @streamV]
              (vreset! streamV nil)
              (sm/close! stream)))
          clojure.lang.IDeref
          (deref [_] @streamV))]

    socket))

(s/def ::ssl-context #(instance? io.netty.handler.ssl.SslContext %))

(s/def ::create-server-opts (s/keys :req [::socket.spec/port
                                          ::socket.spec/host]
                                    :opt [::ssl-context]))

(defn create-server
  [{:as opts
    :keys [::socket.spec/port
           ::socket.spec/host
           ::socket.spec/evt|
           ::socket.spec/connection|
           ::socket.spec/ex|]
    :or {host "0.0.0.0"
         port 0}}]
  (let [serverV (volatile! nil)
        socket-server
        ^{:type ::socket.spec/socket-server}
        (reify
          socket.protocols/SocketServer
          (listen*
            [t]
            (try
              (let [server (aleph.tcp/start-server
                            (fn socket-server-handler
                              [stream {:keys [remote-addr ssl-session server-port server-name] :as info}]
                              (put! connection| {::socket.spec/socket
                                                 (doto
                                                  (create {::socket-stream stream
                                                           ::socket.spec/evt| (chan (sliding-buffer 10))
                                                           ::socket.spec/msg| (chan 100)
                                                           ::socket.spec/ex| (chan 1)})
                                                   (socket.protocols/connect*))
                                                 :info info}))
                            {:socket-address (InetSocketAddress. ^String host ^int port)})]
                (vreset! serverV server)
                (put! evt| {:op :listening}))
              (catch Exception ex (do
                                    (put! ex| ex)
                                    (socket.protocols/close* t)))))
          socket.protocols/Close
          (close*
            [_]
            (when-let [server @serverV]
              (.close ^java.io.Closeable server)))
          clojure.lang.IDeref
          (deref [_] @serverV))]

    socket-server))

(comment


  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/bytes-jvm"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/bytes-meta"}
                      github.cljctools/socket-jvm {:local/root "./cljctools/socket-jvm"}}}'
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    (require '[cljctools.socket.core :as socket.core])
    (require '[manifold.deferred :as d])
    (require '[manifold.stream :as sm]))


  (def s (sm/stream))
  (sm/consume #(prn %) s)
  (sm/put! s 1)

  ;
  )


(comment


  (def server (aleph.tcp/start-server
               (fn [stream info]
                 (println (type stream)) ; manifold.stream.SplicedStream
                 (println info) ; {:remote-addr 127.0.0.1, :ssl-session nil, :server-port 3000, :server-name localhost}
                 )
               {:port 3000}))

  (def socket @(aleph.tcp/client {:host "localhost" :port 3000 :insecure? true}))

  (instance? manifold.stream.SplicedStream socket)
  ; true

  (.close server)


  (d/chain
   {:foo 1}
   (fn [x]
     (println :x x)))

  ;
  )