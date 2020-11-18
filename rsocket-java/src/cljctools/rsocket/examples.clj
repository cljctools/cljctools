(ns cljctools.rsocket.examples
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
   reactor.core.publisher.Flux
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


(comment

  (defn make-rsocket-recv
    [name]
    (reify RSocket
      (requestResponse [_ payload]
        (println (format "requestResponse %s answers to: %s" name (.getDataUtf8 payload)))
        (Mono/just (DefaultPayload/create (format "echo %s" (.getDataUtf8 payload)))))
      (requestStream [_ payload]
        (println (format "requestStream %s answers to: %s" name (.getDataUtf8 payload)))
        (-> #_(Flux/interval (Duration/ofMillis 1))
            (Flux/range 0 10)
            (.map (reify Function
                    (apply [_ a-long]
                      (DefaultPayload/create (format "interval %s" a-long)))))))))

  (defn request-response
    [client name text]
    (-> client
        (.requestResponse (Mono/just (DefaultPayload/create (format "%s asks '%s'" name text))))
        (.doOnSubscribe (reify Consumer
                          (accept [_ s]
                            (println (format "%s : executing request" name)))))
        (.doOnNext (reify Consumer
                     (accept [_ payload]
                       (println (format "requestResponse %s receives: %s" name (.getDataUtf8 payload)))
                       (.release payload))))
        (.subscribe)
        #_(.repeat 0)
        #_(.blockLast)))

  (defn request-stream
    [client name text]
    (-> client
        (.requestStream (Mono/just (DefaultPayload/create (format "%s asks '%s'" name text))))
        #_(.map (reify Function
                  (apply [_ payload]
                    (.getDataUtf8 payload))))
        #_(.log)
        (.doOnNext (reify Consumer
                     (accept [_ payload]
                       (println (format "request-stream %s receives: %s" name (.getDataUtf8 payload)))
                       (.release payload))))
        (.subscribe)))

  ;; accepting side

  (def accepting-client (atom nil))
  (def accepting-server (->  #_(RSocketServer/create (SocketAcceptor/with rsocket))
                             (RSocketServer/create
                              (reify SocketAcceptor
                                (accept [_ setup rsocket-send]
                                  (reset! accepting-client (RSocketClient/from rsocket-send))
                                  (Mono/just (make-rsocket-recv "accepting")))))
                             (.bind (TcpServerTransport/create "localhost" 7000))
                             (.doOnNext (reify Consumer
                                          (accept [_ cc]
                                            (println (format "server started on address %s" (.address cc))))))
                             (.subscribe)))

  ;; connecting side

  (def connecting-source
    (-> (RSocketConnector/create)
        (.acceptor (SocketAcceptor/with (make-rsocket-recv "connecting")))
        (.reconnect (Retry/backoff 50 (Duration/ofMillis 500)))
        (.connect (TcpClientTransport/create "localhost" 7000))
        (.block)))

  (def connecting-client (RSocketClient/from connecting-source))

  (request-response connecting-client "connecting" "ping")
  (request-response @accepting-client "accepting" "ping")

  (request-stream connecting-client "connecting" "stream?")
  (request-stream @accepting-client "accepting" "stream?")


  ;;
  )