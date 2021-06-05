(ns cljctools.rsocket.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe toggle
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [clojure.string :as string]
   [cljs.reader :refer [read-string]]
   #_[cljs.nodejs :as node]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.rsocket.spec :as rsocket.spec]
   [cljctools.rsocket.chan :as rsocket.chan]
   [cljctools.rsocket.protocols :as rsocket.protocols]

   ["rsocket-core" :refer (RSocketClient RSocketServer MAX_STREAM_ID)]
   ["rsocket-flowable" :refer (Flowable) :rename {Single FSingle}]
   ["rsocket-websocket-client" :default RSocketWebSocketClient]))

(declare RSocketWebSocketServer RSocketTCPServer RSocketTcpClient node-require)

(when (exists? js/module)
  (def RSocketWebSocketServer (.-default (js/require "rsocket-websocket-server")))
  (def RSocketTCPServer (.-default (js/require "rsocket-tcp-server")))
  (def RSocketTcpClient (.-default (js/require "rsocket-tcp-client")))
  (def ws (js/require "ws"))
  (def WSServer (.-Server ws))
  (set! js/WebSocket ws)
  #_(set! js/module.exports exports))

#_(when (exists? js/module)
    (require '[cljs.nodejs])
    (def node-require (resolve 'cljs.nodejs/require))
    (def RSocketWebSocketServer (.-default (node-require "rsocket-websocket-server")))
    (def RSocketTCPServer (.-default (node-require "rsocket-tcp-server")))
    (def RSocketTcpClient (.-default (node-require "rsocket-tcp-client")))
    (set! js/WebSocket (node-require "ws"))
    #_(set! js/module.exports exports))

#_(do
  (def fs (node/require "fs"))
  (def path (node/require "path"))
  (def RSocketClient (.-RSocketClient (node/require "rsocket-core")))
  (def MAX_STREAM_ID (.-MAX_STREAM_ID (node/require "rsocket-core")))
  (def RSocketServer (.-RSocketServer (node/require "rsocket-core")))
  (def Flowable (.-Flowable (node/require "rsocket-flowable")))
  (def FSingle (.-Single (node/require "rsocket-flowable")))
  (def RSocketWebSocketServer (.-default (node/require "rsocket-websocket-server")))
  (def RSocketWebSocketClient (.-default (node/require "rsocket-websocket-client")))
  (def RSocketTCPServer (.-default (node/require "rsocket-tcp-server")))
  (def RSocketTcpClient (.-default (node/require "rsocket-tcp-client"))))

(defn create-proc-ops
  [channels opts]
  (let [{:keys [::rsocket.chan/ops|
                ::rsocket.chan/release|
                ::rsocket.chan/requests|]} channels
        {:keys [::rsocket.spec/connection-side
                ::rsocket.spec/host
                ::rsocket.spec/port
                ::rsocket.spec/transport
                ::rsocket.spec/create-websocket
                ::rsocket.spec/create-websocket-server]

         :or {create-websocket
              (fn [url]
                (js/WebSocket. (str "ws://" host ":" port)))
              create-websocket-server
              (fn [options]
                (WSServer. (clj->js {"host" host
                                     "port" port})))}} opts

        ops*| (chan (sliding-buffer 5))
        ops*|mx (mix ops*|)
        toggle-pause (fn [pause?]
                       (toggle ops*|mx {ops| {:pause pause?}}))
        _ (admix ops*|mx ops|)
        _ (toggle-pause true)

        rsocket-response
        (clj->js
         {"requestResponse"
          (fn [payload]
            (let [value (read-string payload.data)
                  out| (chan 1)]
              (put! requests| (merge value
                                     {::op.spec/out| out|}))
              (FSingle.
               (fn [subscriber]
                 (.onSubscribe subscriber)
                 (take! out| (fn [value]
                               #_(println "sending value back")
                               #_(println (dissoc value ::op.spec/out|))
                               (.onComplete subscriber (clj->js
                                                        {"data" (pr-str (dissoc value ::op.spec/out|))
                                                         "metadata" ""}))))))))
          "fireAndForget"
          (fn [payload]
            (put! requests| (read-string payload.data)))
          "requestStream"
          (fn [payload]
            (let [value (read-string payload.data)
                  out| (chan 64)]
              (put! requests| (merge value
                                     {::op.spec/out| out|}))
              (Flowable.
               (fn [subscriber]
                 (let [cancelled (volatile! false)]
                   (.onSubscribe
                    subscriber
                    (clj->js
                     {"cancel" (fn []
                                 (vreset! cancelled true)
                                 (println ::cancelled @cancelled))
                      "request" (fn [n]
                                  (go (loop []
                                        (when-not @cancelled
                                          (when-let [value (<! out|)]
                                            #_(println "will rsocket send " value)
                                            (.onNext subscriber
                                                     (clj->js
                                                      {"data" (pr-str (dissoc value ::op.spec/out|))
                                                       "metadata" ""}))
                                            (recur))))
                                      (when-not @cancelled
                                        (.onComplete subscriber))))})))))))
          "requestChannel"
          (fn [payloads]
            (let [out| (chan 64)
                  send| (chan 64)
                  first-value? (atom true)]
              (-> payloads
                  (.subscribe
                   (clj->js
                    {"onComplete" (fn []
                                    (println ::onComplete))
                     "onError" (fn [error]
                                 (println ::onError))
                     "onNext" (fn [payload]
                                (let [value (read-string payload.data)]
                                  (if @first-value?
                                    (do
                                      (put! requests| (merge value
                                                             {::op.spec/out| out|
                                                              ::op.spec/send| send|}))
                                      (reset! first-value? false))
                                    (do
                                      (put! out| value)))))
                     "onSubscribe" (fn [subscription]
                                     (.request subscription MAX_STREAM_ID))})))
              (Flowable.
               (fn [subscriber]
                 (let [cancelled (volatile! false)]
                   (.onSubscribe
                    subscriber
                    (clj->js {"cancel" (fn []
                                         (vreset! cancelled true))
                              "request" (fn [n]
                                          (go (loop []
                                                (when-not @cancelled
                                                  (when-let [value (<! out|)]
                                                    (.onNext subscriber
                                                             (clj->js
                                                              {"data" (pr-str (dissoc value ::op.spec/out|))
                                                               "metadata" ""}))
                                                    (recur))))
                                              (when-not @cancelled
                                                (.onComplete subscriber))))})))))))})

        clients (atom {})
        connection (atom nil)

        create-connection-accepting
        (fn []
          (let [rsocket-server
                (RSocketServer.
                 (clj->js
                  {"getRequestHandler"
                   (fn [rsocket-request]
                     (-> rsocket-request
                         (.connectionStatus)
                         (.subscribe (fn [status]
                                       (cond
                                         (= (.-kind status) "CONNECTED")
                                         (do
                                           (println ::accepting-side :rsocket-connected)
                                           (swap! clients assoc rsocket-request rsocket-request)
                                           (toggle-pause false))
                                         (= (.-kind status) "CLOSED")
                                         (do
                                           (println ::accepting-side :rsocket-disconnected)
                                           (swap! clients dissoc rsocket-request)
                                           (when (empty? @clients)
                                             (toggle-pause true)))))))
                     rsocket-response)
                   "transport" (condp = transport
                                 ::rsocket.spec/tcp (RSocketTCPServer.
                                                     (clj->js {"host" host
                                                               "port" port}))
                                 ::rsocket.spec/websocket (RSocketWebSocketServer.
                                                           (clj->js {"host" host
                                                                     "port" port})
                                                           nil
                                                           create-websocket-server))
                   "errorHandler" (fn [error]
                                    (println ::error)
                                    (println error))}))]
            (-> rsocket-server
                (.start))
            rsocket-server))

        create-connection-initiating
        (fn []
          (let [rsocket-client
                (RSocketClient.
                 (clj->js
                  {"setup" {"dataMimeType"  "text/plain"
                            "keepAlive" #_js/Number.MAX_SAFE_INTEGER #_js/Infinity 1000000000
                            "lifetime" #_js/Number.MAX_SAFE_INTEGER #_js/Infinity 100000000
                            "metadataMimeType" "text/plain"}
                   "responder" rsocket-response
                   "transport" (condp = transport
                                 ::rsocket.spec/tcp (RSocketTcpClient. (clj->js {"host" host
                                                                                 "port" port}))
                                 ::rsocket.spec/websocket (RSocketWebSocketClient.
                                                           (clj->js {"url" nil
                                                                     "wsCreator" create-websocket})))}))]
            (->
             rsocket-client
             (.connect)
             (.subscribe
              (clj->js
               {"onComplete" (fn [rsocket-request]
                               (do (-> rsocket-request
                                       (.connectionStatus)
                                       (.subscribe (fn [status]
                                                     (cond
                                                       (= (.-kind status) "CONNECTED")
                                                       (do
                                                         (println ::initiating-side :rsocket-connected)
                                                         (swap! clients assoc rsocket-request rsocket-request)
                                                         (toggle-pause false))
                                                       (= (.-kind status) "CLOSED")
                                                       (do
                                                         (println ::initiating-side :rsocket-disconnected)
                                                         (swap! clients dissoc rsocket-request)
                                                         (when (empty? @clients)
                                                           (toggle-pause true)))))))))
                "onError" (fn [error]
                            (println ::initiating-side-error)
                            (println error))
                "onSubscribe" (fn [cancel]
                                #_(cancel))}))
             #_(.then
                (fn [rsocket-request]
                  (reset! client rsocket-request)
                  (do (-> rsocket-request
                          (.connectionStatus)
                          (.subscribe (fn [status]
                                        (close! rsocket-request-intialized|)
                                        (println ::connection-status status.kind))))))))
            rsocket-client))

        request-response
        (fn [value out|]
          (doseq [[k client] (take 1 @clients)]
            (-> client
                (.requestResponse (clj->js
                                   {"data" (pr-str value)
                                    "metadata" ""}))
                (.subscribe
                 (clj->js {"onComplete" (fn [payload]
                                          #_(println (type payload.data))
                                          #_(println "response" payload.data)
                                          (put! out| (read-string payload.data)))
                           "onError" (fn [error]
                                       (put! out| error))})))))
        fire-and-forget
        (fn [value]
          (doseq [[k client] @clients]
            (-> client
                (.fireAndForget (clj->js
                                 {"data" (pr-str value)
                                  "metadata" ""}))
                #_(.subscribe
                   (clj->js {"onComplete" (fn []
                                            #_(println ::onComplete))
                             "onError" (fn [error]
                                         (println ::onError error))})))))
        request-stream
        (fn [value out|]
          (doseq [[k client] (take 1 @clients)]
            (-> client
                (.requestStream (clj->js
                                 {"data" (pr-str value)
                                  "metadata" ""}))
                (.subscribe
                 (clj->js {"onComplete" (fn []
                                          #_(println ::onComplete))
                           "onError" (fn [error]
                                       (put! out| error))
                           "onNext" (fn [payload]
                                      (put! out| (read-string payload.data)))
                           "onSubscribe" (fn [subscription]
                                           (.request subscription MAX_STREAM_ID))})))))

        request-channel
        (fn [value out| send|]
          (doseq [[k client] (take 1 @clients)]
            (-> client
                (.requestChannel
                 (Flowable.
                  (fn [subscriber]
                    (let [cancelled (volatile! false)]
                      (.onSubscribe subscriber
                                    (clj->js {"cancel" (fn []
                                                         (vreset! cancelled true))
                                              "request" (fn [n]
                                                          (.onNext subscriber (pr-str value))
                                                          (go (loop []
                                                                (when-let [value (<! send|)]
                                                                  (.onNext subscriber (pr-str (dissoc value ::op.spec/out|)))
                                                                  (recur)))
                                                              (.onComplete subscriber)))}))))))
                (.subscribe
                 (clj->js {"onComplete" (fn []
                                          #_(println ::onComplete))
                           "onError" (fn [error]
                                       (put! out| error))
                           "onNext" (fn [payload]
                                      (put! out| (read-string payload.data)))
                           "onSubscribe" (fn [subscription]
                                           (.request subscription MAX_STREAM_ID))})))))

        release
        (fn []
          (go
            (when (= connection-side ::rsocket.spec/accepting)
              (.stop @connection))
            (when (= connection-side ::rsocket.spec/initiating)
              (.close @connection))))]
    (when (= connection-side ::rsocket.spec/accepting)
      (reset! connection (create-connection-accepting)))
    (when (= connection-side ::rsocket.spec/initiating)
      (reset! connection  (create-connection-initiating)))
    (go
      (loop []
        (when-let [[value port] (alts! [ops*|])]
          (condp = port

            release|
            (let [{:keys [::op.spec/out|]} value]
              (<! (release))
              (close! out|))

            ops*|
            (do
              (condp = (select-keys value [::op.spec/op-type ::op.spec/op-orient])

                {::op.spec/op-type ::op.spec/request-response
                 ::op.spec/op-orient ::op.spec/request}
                (let [{:keys [::op.spec/out|]} value]
                  (request-response (dissoc value ::op.spec/out|)  out|))

                {::op.spec/op-type ::op.spec/fire-and-forget}
                (let [{:keys []} value]
                  (fire-and-forget value))

                {::op.spec/op-type ::op.spec/request-stream
                 ::op.spec/op-orient ::op.spec/request}
                (let [{:keys [::op.spec/out|]} value]
                  (request-stream  (dissoc value ::op.spec/out|) out|))

                {::op.spec/op-type ::op.spec/request-channel
                 ::op.spec/op-orient ::op.spec/request}
                (let [{:keys [::op.spec/out|
                              ::op.spec/send|]} value]
                  (request-channel (dissoc value ::op.spec/out| ::op.spec/send|) out| send|)))
              (recur))))))))




(comment


  (def fs (node/require "fs"))
  (def path (node/require "path"))

  (def WebSocket (node/require "ws"))
  (def RSocketClient (.-RSocketClient (node/require "rsocket-core")))
  (def MAX_STREAM_ID (.-MAX_STREAM_ID (node/require "rsocket-core")))
  (def RSocketServer (.-RSocketServer (node/require "rsocket-core")))
  (def Flowable (.-Flowable (node/require "rsocket-flowable")))
  (def FSingle (.-Single (node/require "rsocket-flowable")))
  (def RSocketWebSocketServer (.-default (node/require "rsocket-websocket-server")))
  (def RSocketWebSocketClient (.-default (node/require "rsocket-websocket-client")))
  (def RSocketTCPServer (.-default (node/require "rsocket-tcp-server")))
  (def RSocketTcpClient (.-default (node/require "rsocket-tcp-client")))

  (do
    (def server (RSocketServer.
                 (clj->js
                  {"getRequestHandler"
                   (fn [socket]
                     (println "server: socket connected")
                     #_(println (js/Object.keys socket))
                     (clj->js {"fireAndForget"
                               (fn [payload]
                                 (println (format "fnf data %s" payload.data))
                                 (println (format "fnf metadata %s" payload.metadata)))
                               "requestResponse"
                               (fn [payload]
                                 (js/console.log (format "requestResponse %s" payload.data))
                                 (FSingle.of (clj->js {"data" (str "hello" payload.data)
                                                       "metadata" ""})))
                               "requestStream"
                               (fn [payload]
                                 (println (format "requestStream %s" payload.data))
                                 (Flowable.just (clj->js {"data" "hello"
                                                          "metadata" ""})
                                                (clj->js {"data" "world"
                                                          "metadata" ""})))}))
                   "transport" (RSocketTCPServer. (clj->js {"host" "0.0.0.0"
                                                            "port" 7000}))})))
    (.start server)
    ;;
    )

  (do
    (def client (->
                 (RSocketClient.
                  (clj->js
                   {"setup" {"dataMimeType"  "text/plain"
                             "keepAlive" 1000000
                             "lifetime" 100000
                             "metadataMimeType" "text/plain"}
                    "responder" {"fireAndForget"
                                 (fn [payload]
                                   (js/console.log (format "client fnf data %s" payload.data))
                                   (println (format "client fnf metadata %s" payload.metadata)))
                                 "requestResponse"
                                 (fn [payload]
                                   (println (format "client requestResponse %s" payload))
                                   (Flowable.just (clj->js {"data" "hello"
                                                            "metadata" ""})))}
                    "transport" (RSocketTcpClient. (clj->js {"host" "0.0.0.0"
                                                             "port" 7000}))}))
                 (.connect)))
    (.then client
           (fn [v]
             (println "then")
             (set! client v)
             (println "keys " (js/Object.keys client))
             (println (format "client.requestResponse is %s" (type client.requestResponse)))
             (do (-> client
                     (.connectionStatus)
                     (.subscribe (fn [status]

                                   (println (format "conn status: %s" status.kind))))))))
    ;;
    )

  (js/Object.keys client)




  ;;
  )

(comment


  ;; requestStream

  (do
    (def ^:dynamic subscription* nil)
    (get subscription* "request")
    (def c| (chan 1))

    (def handlers
      (let [subscription (atom nil)]
        (clj->js
         {"onComplete" (fn []
                         (println "on-complete")
                         (close! c|))
          "onError" (fn [error]
                      (put! c| error)
                      (close! c|))
          "onNext" (fn [payload]
                     (println (format "on next %s" payload.data))
                     (put! c| payload.data))
          "onSubscribe" (fn [s]
                          (println "on subscribe")
                          (reset! subscription s)
                          (println (js/Object.keys @subscription))
                          (println (js-keys @subscription))
                          (println (js/Object.getOwnPropertyNames @subscription))
                          (println (type s))
                          (println s)
                          (set! subscription* s)
                          (.request s MAX_STREAM_ID)
                          #_(.request @subscription MAX_STREAM_ID))})))



    (go (loop []
          (when-let [v (<! c|)]
            (println v)
            (recur))))
    ;;
    )

  (->
   (.requestStream client (clj->js {"data" "122"
                                    "metadata" "1"}))
   (.subscribe handlers))


  ;;
  )


(comment

  ;; requestResponse

  (do
    (def c| (chan 1))

    (def handlers
      (let [subscription (atom nil)]
        (clj->js
         {"onComplete" (fn [response]
                         (println "on-complete")
                         (println response.data)
                         (close! c|))
          "onError" (fn [error]
                      (put! c| error)
                      (close! c|))})))


    (go (loop []
          (when-let [v (<! c|)]
            (println v)
            (recur))))
    ;;
    )

  (->
   (.requestResponse client (clj->js {"data" "12"
                                      "metadata" "1"}))
   (.subscribe handlers))

  ;;
  )