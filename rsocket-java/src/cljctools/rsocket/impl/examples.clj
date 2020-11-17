(ns cljctools.rsocket.impl.examples
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string :as str]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core])

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
   reactor.util.retry.Retry
   java.util.function.Function
   java.util.function.Consumer))

(comment

  (def server
    (-> (RSocketServer/create
         (SocketAcceptor/forRequestResponse
          (reify Function
            (apply [_ payload]
              (let [data (.getDataUtf8 payload)
                    _ (println (format "received request data %s" data))
                    response-payload (DefaultPayload/create (str "Echo: " data))]
                (.release payload)
                (Mono/just response-payload))))))
        (.bind (TcpServerTransport/create "localhost" 7000))
        (.delaySubscription (Duration/ofSeconds 5))
        (.doOnNext (reify Consumer
                     (accept [_ cc]
                       (println (format "server started on address %s" (.address cc))))))
        (.subscribe)))

  (def source
    (-> (RSocketConnector/create)
        (.reconnect (Retry/backoff 50 (Duration/ofMillis 500)))
        (.connect (TcpClientTransport/create "localhost" 7000))))

  (def client
    (-> (RSocketClient/from source)
        (.requestResponse (Mono/just (DefaultPayload/create "test request")))
        (.doOnSubscribe (reify Consumer
                          (accept [_ s]
                            (println "executing request"))))
        (.doOnNext (reify Consumer
                     (accept [_ payload]
                       (println (format "received response data %s" (.getDataUtf8 payload)))
                       (.release payload))))
        (.repeat 10)
        (.blockLast)))



  ;;
  )