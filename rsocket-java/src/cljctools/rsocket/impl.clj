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
  (let [{:keys [::rsocket.chan/ops|
                ::rsocket.chan/requests|]} channels
        {:keys [::rsocket.spec/connection-side
                ::rsocket.spec/host
                ::rsocket.spec/port]} opts

        rsocket-response
        (reify RSocket
          (requestResponse
            [_ payload]
            (let [value (read-string (.getDataUtf8 payload))
                  out| (chan 1)]
              (.release payload)
              (put! requests| (merge value
                                     {::op.spec/out| out|}))
              (Mono/create
               (reify Consumer
                 (accept [_ sink]
                   (take! out| (fn [value]
                                 (.success sink (pr-str value)))))))))
          (fireAndForget
            [_ payload]
            (let [value (read-string (.getDataUtf8 payload))]
              (.release payload)
              (put! requests| value)
              (Mono/empty)))
          (requestStream
            [_ payload]
            (let [value (read-string (.getDataUtf8 payload))
                  out| (chan 64)]
              (put! requests| (merge value
                                     {::op.spec/out| out|}))
              (.release payload)
              (Flux/create
               (reify Consumer
                 (accept [_ emitter]
                   (go (loop []
                         (when-let [v (<! out|)]
                           (.next emitter v)
                           (recur)))))))))
          (requestChannel
            [_ payloads]
            (let [out| (chan 64)
                  send| (chan 64)
                  first-value? (atom true)]
              (-> (Flux/from payloads)
                  (.doOnNext
                   (reify Consumer
                     (accept [_ payload]
                       (let [value (read-string (.getDataUtf8 payload))]
                         (.release payload)
                         (if @first-value?
                           (do
                             (put! requests| (merge value
                                                    {::op.spec/out| out|
                                                     ::op.spec/send| send|})))
                           (do
                             (put! out| value))))))))
              (Flux/create
               (reify Consumer
                 (accept [_ emitter]
                   (go (loop []
                         (when-let [v (<! send|)]
                           (.next emitter v)
                           (recur))))))))))

        client (atom nil)

        create-connection-accepting (fn []
                                      (->  #_(RSocketServer/create (SocketAcceptor/with rsocket))
                                           (RSocketServer/create
                                            (reify SocketAcceptor
                                              (accept [_ setup rsocket-request]
                                                (reset! client (RSocketClient/from rsocket-request))
                                                (Mono/just rsocket-response))))
                                           (.bind (TcpServerTransport/create host port))
                                           (.doOnNext (reify Consumer
                                                        (accept [_ cc]
                                                          (println (format "server started on address %s" (.address cc))))))
                                           (.subscribe)))

        create-connection-initiating (fn []
                                       (let [source
                                             (-> (RSocketConnector/create)
                                                 (.acceptor (SocketAcceptor/with rsocket-response))
                                                 (.reconnect (Retry/backoff 50 (Duration/ofMillis 500)))
                                                 (.connect (TcpClientTransport/create host port))
                                                 (.block))]))

        create-cleint-initiating (fn [source]
                                   (RSocketClient/from source))

        connection (atom nil)

        request-response (fn [value out|]
                           (-> @client
                               (.requestResponse (Mono/just (DefaultPayload/create value)))
                               (.doOnNext (reify Consumer
                                            (accept [_ payload]
                                              (put! payload out|)
                                              (.release payload))))
                               (.subscribe)))
        fire-and-forget (fn [value]
                          (-> @client
                              (.fireAndForget (Mono/just (DefaultPayload/create value)))
                              (.subscribe)))
        request-stream (fn [value out|]
                         (-> @client
                             (.requestStream (Mono/just (DefaultPayload/create value)))
                             (.doOnNext (reify Consumer
                                          (accept [_ payload]
                                            (put! payload out|)
                                            (.release payload))))
                             (.subscribe)))
        request-channel (fn [value out| send|]
                          (-> @client
                              (.requestChannel (Flux/create
                                                (reify Consumer
                                                  (accept [_ emitter]
                                                    (.next emitter value)
                                                    (go (loop []
                                                          (when-let [v (<! send|)]
                                                            (.next emitter v)
                                                            (recur))))))))
                              (.doOnNext (reify Consumer
                                           (accept [_ payload]
                                             (put! payload out|)
                                             (.release payload))))
                              (.subscribe)))]
    (when (= connection-side ::rsocket.spec/accepting)
      (reset! connection (create-connection-accepting)))
    (when (= connection-side ::rsocket.spec/initiating)
      (reset! connection (create-connection-initiating))
      (reset! client (create-cleint-initiating @connection)))
    (go
      (loop []
        (when-let [[value port] (alts! [ops|])]
          (condp = port

            ops|
            (condp = (select-keys value [::op.spec/op-type ::op.spec/op-orient])

              {::op.spec/op-type ::op.spec/request-response
               ::op.spec/op-orient ::op.spec/request}
              (let [{:keys [::op.spec/out|]} value]
                (request-response (dissoc value ::op.spec/out|)  out|))

              {::op.spec/op-type ::op.spec/request-response
               ::op.spec/op-orient ::op.spec/request}
              (let [{:keys [::op.spec/out|]} value]
                (request-response (dissoc value ::op.spec/out|) out|))

              {::op.spec/op-type ::op.spec/request-stream
               ::op.spec/op-orient ::op.spec/request}
              (let [{:keys [::op.spec/out|]} value]
                (request-stream  (dissoc value ::op.spec/out|) out|))

              {::op.spec/op-type ::op.spec/request-channel
               ::op.spec/op-orient ::op.spec/request}
              (let [{:keys [::op.spec/out|
                            ::op.spec/send|]} value]
                (request-channel (dissoc value ::op.spec/out| ::op.spec/send|) out| send|))

              ;; default
              ;; deafult means fire-and-forget, for any value 
              (let []
                (fire-and-forget value)))))))))


(comment
  
  
  
  ;;
  )
