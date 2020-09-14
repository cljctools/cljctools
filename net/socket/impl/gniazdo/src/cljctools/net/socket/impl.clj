(ns cljctools.net.socket.api
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [gniazdo.core :as gniazdo.api]
   [cljctools.net.core.protocols :as p]
   [cognitect.transit :as transit])
  (:import
   org.eclipse.jetty.websocket.api.Session
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.net.URI
   java.nio.ByteBuffer))


(defn create-proc-ws
  [channels ctx opts]
  (let [{:keys [ws-evt| ws-recv|]} channels
        {:keys [url]} opts
        send| (chan 10)
        baos (ByteArrayOutputStream. 4096)
        transit-writer (transit/writer baos :json)
        transit-read (fn [payload]
                       (let [bais (ByteArrayInputStream. payload)
                             transit-reader (transit/reader bais :json)
                             data (transit/read transit-reader)]
                         data))
        socket (gniazdo.api/connect
                url
                :on-receive (fn [txt] (put! ws-recv| {:op :ws/recv :data txt}))
                :on-binary (fn [payload offset length]
                             (put! ws-recv| {:op :ws/recv
                                             :data (transit-read payload)})))
        send-data (fn [v]
                    (transit/write transit-writer v)
                    (gniazdo.api/send-msg socket (ByteBuffer/wrap (.toByteArray baos)))
                    (.reset baos))
        state (atom {:socket socket
                     :send| send|})]
    (go
      (loop []
        (when-let [[v port] (alts! [send|])]
          (condp = port
            send| (let []
                    (send-data v))))
        (recur))
      (println "; proc-ws go-block exiting"))
    (reify
      p/Connect
      (-connect [_])
      (-disconnect [_])
      (-connected? [_])
      p/Send
      (-send [_ data]
        (put! send| data))
      clojure.lang.ILookup
      (valAt [_ k] (.valAt _ k nil))
      (valAt [_ k not-found] (.valAt @state k not-found)))))


(defn send-data
  [_ data]
  (p/-send _ data))