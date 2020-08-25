(ns cljctools.net.socket.api
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [goog.string :as gstring]
   [goog.string.format]
   [cognitect.transit :as transit])
  (:import
   [goog.net XhrIo EventType WebSocket]
   [goog Uri]
   [goog.history.Html5History]))


(comment

  (def w (transit/writer :json))
  (def r (transit/reader :json))

  (def ws (WebSocket. #js {:autoReconnect false}))

  (.open ws "ws://0.0.0.0:8080/ws")

  (.listen ws WebSocket.EventType.MESSAGE
           (fn [^:goog.net.WebSocket.MessageEvent ev]
             (let [blob (.-message ev)]
               (-> blob
                   (.text)
                   (.then (fn [s]
                            (let [o (transit/read r s)]
                              (prn o)
                              #_(put! ch-socket-in o))))))))

  (def data-transit (transit/write w {:a 1}))
  (def blob (js/Blob. [data-transit] #js {:type "application/transit+json"}))
  (-> blob
      (.arrayBuffer)
      (.then (fn [ab]
               (.send ws ab))))

  ;;
  )