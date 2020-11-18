(ns cljctools.rsocket.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string :as str]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.rsocket.spec :as rsocket.spec]
   [cljctools.rsocket.chan :as rsocket.chan]
   [cljctools.rsocket.protocols :as rsocket.protocols])
  (:import
   io.rsocket.Payload
   io.rsocket.RSocket
   io.rsocket.SocketAcceptor
   io.rsocket.core.RSocketClient
   io.rsocket.core.RSocketConnector
   io.rsocket.core.RSocketServer
   io.rsocket.transport.netty.client.TcpClientTransport
   io.rsocket.transport.netty.server.TcpServerTransport
   io.rsocket.util.DefaultPayload
   java.time.Duration
   reactor.core.publisher.Mono
   reactor.core.publisher.Flux
   reactor.core.publisher.MonoSink
   reactor.core.publisher.FluxSink
   reactor.util.retry.Retry
   java.util.function.Function
   java.util.function.Consumer))


(defn create-proc-ops
  [channels opts]
  (let [{:keys [::rsocket.chan/ops|]} channels

        rsocket-responder
        (reify RSocket
          (requestResponse
            [_ payload]
            (do (.getDataUtf8 payload))
            (let [out| (chan 1)]
              (Mono/create
               (reify Consumer
                 (accept [_ ^MonoSink sink]
                   (take! out| (fn [v]
                                 (.success sink v))))))))
          (fireAndForget
            [_ payload]
            (do (.getDataUtf8 payload))
            (let [out| (chan 64)]
              (Mono/empty)))
          (requestStream
            [_ payload]
            (do (.getDataUtf8 payload))
            (let [out| (chan 64)]
              (Flux/create
               (reify Consumer
                 (accept [_ ^FluxSink emitter]
                   (go (loop []
                         (when-let [v (<! out|)]
                           (.next emitter v)
                           (recur)))))))))
          (requestChannel
            [_ payloads]
            (let [out| (chan 64)
                  send| (chan 64)]
              (-> (Flux/from payloads)
                  (.doOnNext
                   (reify Consumer
                     (accept [_ payload]
                       (put! out| (.getDataUtf8 payload))
                       (.release payload)))))
              (Flux/create
               (reify Consumer
                 (accept [_ ^FluxSink emitter]
                   (go (loop []
                         (when-let [v (<! send|)]
                           (.next emitter v)
                           (recur))))))))))]
    (go
      (loop []
        (when-let [[v port] (alts! [ops|])]
          (condp = port

            ops|
            (condp = (select-keys v [::op.spec/op-key ::op.spec/op-type])

              {::op.spec/op-key ::request-response
               ::op.spec/op-type ::op.spec/request-response
               ::op.spec/op-orient ::op.spec/request}
              (let []))))))))


(comment
  
  
  
  ;;
  )



(defn create-proc-ops
  [channels opts]
  (let [{:keys [::rsocket.chan/ops|
                ::rsocket.chan/evt|m
                ::rsocket.chan/send|
                ::rsocket.chan/recv|]} channels]
    (go
      (loop []
        (when-let [[v port] (alts! [ops|])])))))
