(ns cljctools.rsocket.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [clojure.string :as string]
   [cljs.reader :refer [read-string]]
   [cljs.nodejs :as node]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.rsocket.spec :as rsocket.spec]
   [cljctools.rsocket.chan :as rsocket.chan]
   [cljctools.rsocket.protocols :as rsocket.protocols]))

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

(defn create-proc-ops
  [channels opts]
  (let [{:keys [::rsocket.chan/ops|
                ::rsocket.chan/requests|]} channels
        {:keys [::rsocket.spec/connection-side
                ::rsocket.spec/host
                ::rsocket.spec/port
                ::rsocket.spec/transport]} opts
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
                               (println "sending value back")
                               (println (dissoc value ::op.spec/out|))
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
                                 (println "cancelled" @cancelled))
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
                                    (println "onComplete"))
                     "onError" (fn [error]
                                 (println "onError"))
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
        
        client (atom nil)
        connection (atom nil)

        create-connection-accepting
        (fn []
          (-> (RSocketServer.
               (clj->js
                {"getRequestHandler"
                 (fn [rsocket-request]
                   (println "getRequestHandler")
                   (reset! client rsocket-request)
                   (println @client)
                   rsocket-response)
                 "transport" (condp = transport
                               ::rsocket.spec/tcp (RSocketTCPServer.
                                                   (clj->js {"host" host
                                                             "port" port}))
                               ::rsocket.spec/websocket (RSocketWebSocketServer.
                                                         (clj->js {"host" host
                                                                   "port" port})))}))
              (.start)))

        create-connection-initiating
        (fn []
          (->
           (RSocketClient.
            (clj->js
             {"setup" {"dataMimeType"  "text/plain"
                       "keepAlive" 1000000
                       "lifetime" 100000
                       "metadataMimeType" "text/plain"}
              "responder" rsocket-response
              "transport" (condp = transport
                            ::rsocket.spec/tcp (RSocketTcpClient. (clj->js {"host" host
                                                                            "port" port}))
                            ::rsocket.spec/websocket (RSocketWebSocketClient.
                                                      (clj->js {"url" (str "ws://" host ":" port)
                                                                "wsCreator" (fn [url]
                                                                              (WebSocket. url))})))}))
           (.connect)
           (.then
            (fn [socket-request]
              (reset! client socket-request)
              (do (-> socket-request
                      (.connectionStatus)
                      (.subscribe (fn [status]
                                    (println (format "conn status: %s" status.kind))))))))))

        request-response
        (fn [value out|]
          (-> @client
              (.requestResponse (clj->js
                                 {"data" (pr-str value)
                                  "metadata" ""}))
              (.subscribe
               (clj->js {"onComplete" (fn [payload]
                                        (println (type payload.data))
                                        (println "response" payload.data)
                                        (put! out| (read-string payload.data)))
                         "onError" (fn [error]
                                     (put! out| error))}))))
        fire-and-forget
        (fn [value]
          (-> @client
              (.fireAndForget (clj->js
                               {"data" (pr-str value)
                                "metadata" ""}))
              (.subscribe
               (clj->js {"onComplete" (fn []
                                        #_(println ::onComplete))
                         "onError" (fn [error]
                                     (println ::onError error))}))))
        request-stream
        (fn [value out|]
          (-> @client
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
                                         (.request subscription MAX_STREAM_ID))}))))

        request-channel
        (fn [value out| send|]
          (-> @client
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
                                         (.request subscription MAX_STREAM_ID))}))))]
    (when (= connection-side ::rsocket.spec/accepting)
      (println ::rsocket.spec/accepting)
      (reset! connection (create-connection-accepting)))
    (when (= connection-side ::rsocket.spec/initiating)
      (reset! client  (create-connection-initiating)))
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