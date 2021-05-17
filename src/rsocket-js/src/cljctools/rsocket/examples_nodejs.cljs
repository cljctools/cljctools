(ns cljctools.rsocket.examples-nodejs
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
   [cljs.nodejs :as node]))

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


(comment

  
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