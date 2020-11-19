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
   io.rsocket.transport.netty.WebsocketDuplexConnection
   io.rsocket.transport.netty.client.WebsocketClientTransport
   io.rsocket.frame.decoder.PayloadDecoder
   reactor.netty.Connection
   reactor.netty.DisposableServer
   reactor.netty.http.server.HttpServer
   reactor.util.retry.Retry
   java.util.function.Function
   java.util.function.Consumer
   java.util.function.BiFunction))


(defn create-proc-ops
  [channels opts]
  (let [{:keys [::rsocket.chan/ops|
                ::rsocket.chan/requests|]} channels
        {:keys [::rsocket.spec/connection-side
                ::rsocket.spec/host
                ::rsocket.spec/port
                ::rsocket.spec/transport]} opts

        rsocket-response
        (reify RSocket
          (requestResponse
            [_ payload]
            (let [value (read-string (.getDataUtf8 payload))
                  out| (chan 1)]
              (.release payload)
              (put! requests| (merge value
                                     {::op.spec/out| out|}))
              #_(Mono/just (DefaultPayload/create (str "Echo: ")))
              (Mono/create
               (reify Consumer
                 (accept [_ sink]
                   (take! out| (fn [value]
                                 (println "sinking value back")
                                 (println (dissoc value ::op.spec/out|))
                                 (.success sink (DefaultPayload/create
                                                 (pr-str (dissoc value ::op.spec/out|)))))))))))
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
                                                     ::op.spec/send| send|}))
                             (reset! first-value? false))
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
        connection (atom nil)

        create-connection-accepting-tcp
        (fn []
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

        create-connection-accepting-ws
        (fn []
          (let [connection-acceptor
                (-> (RSocketServer/create
                     (reify SocketAcceptor
                       (accept [_ setup rsocket-request]
                         (reset! client (RSocketClient/from rsocket-request))
                         (Mono/just rsocket-response))))
                    (.payloadDecoder PayloadDecoder/ZERO_COPY)
                    (.asConnectionAcceptor))]
            (-> (HttpServer/create)
                (.host host)
                (.port port)
                (.route
                 (reify Consumer
                   (accept [_ routes]
                     (.get routes "/"
                           (reify BiFunction
                             (apply [_ req res]
                               (.sendWebsocket res (reify BiFunction
                                                     (apply [_ in  out]
                                                       (-> connection-acceptor
                                                           (.apply (WebsocketDuplexConnection. in))
                                                           (.then (.neverComplete out))))))))))))
                (.bindNow))))

        create-connection-initiating-tcp
        (fn []
          (-> (RSocketConnector/create)
              (.acceptor (SocketAcceptor/with rsocket-response))
              (.reconnect (Retry/backoff 50 (Duration/ofMillis 500)))
              (.connect (TcpClientTransport/create host port))
              (.block)))

        create-cleint-initiating-tcp
        (fn [rsocket-request]
          (RSocketClient/from rsocket-request))


        create-connection-initiating-ws
        (fn
          []
          (-> (RSocketConnector/create)
              (.acceptor (SocketAcceptor/with rsocket-response))
              (.keepAlive (Duration/ofMinutes 10)  (Duration/ofMinutes 10))
              (.payloadDecoder PayloadDecoder/ZERO_COPY)
              (.connect (WebsocketClientTransport/create host port))
              (.block)))

        create-cleint-initiating-ws
        (fn [rsocket-request]
          (RSocketClient/from rsocket-request))


        request-response
        (fn [value out|]
          (-> @client
              (.requestResponse (Mono/just (DefaultPayload/create (pr-str value))))
              (.doOnNext (reify Consumer
                           (accept [_ payload]
                             (let [value (read-string (.getDataUtf8 payload))]
                               (println (str connection-side " request-response value:"))
                               (println value)
                               (put! out| value))
                             (.release payload))))
              (.subscribe)))

        fire-and-forget
        (fn [value]
          (-> @client
              (.fireAndForget (Mono/just (DefaultPayload/create (pr-str value))))
              (.subscribe)))


        request-stream
        (fn [value out|]
          (-> @client
              (.requestStream (Mono/just (DefaultPayload/create (pr-str value))))
              (.doOnNext (reify Consumer
                           (accept [_ payload]
                             (let [value (read-string (.getDataUtf8 payload))]
                               (println "recived value ")
                               (put! out| value))
                             (.release payload))))
              (.subscribe)))

        request-channel
        (fn [value out| send|]
          (-> @client
              (.requestChannel (Flux/create
                                (reify Consumer
                                  (accept [_ emitter]
                                    (.next emitter (pr-str value))
                                    (go (loop []
                                          (when-let [value (<! send|)]
                                            (.next emitter (pr-str value))
                                            (recur))))))))
              (.doOnNext (reify Consumer
                           (accept [_ payload]
                             (put! (read-string (.getDataUtf8 payload)) out|)
                             (.release payload))))
              (.subscribe)))]
    (when (= connection-side ::rsocket.spec/accepting)
      (reset! connection (condp = transport
                           ::rsocket.spec/tcp (create-connection-accepting-tcp)
                           ::rsocket.spec/websocket (create-connection-accepting-ws))))
    (when (= connection-side ::rsocket.spec/initiating)
      (reset! connection  (condp = transport
                            ::rsocket.spec/tcp (create-connection-initiating-tcp)
                            ::rsocket.spec/websocket (create-connection-initiating-ws)))
      (reset! client (condp = transport
                       ::rsocket.spec/tcp (create-cleint-initiating-tcp @connection)
                       ::rsocket.spec/websocket (create-cleint-initiating-ws @connection))))
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
  
  (Mono/create
   (reify Consumer
     (accept [_ sink]
       (.success sink "foo"))))
  
  ;;
  )