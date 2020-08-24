(ns cljctools.net.socket.api
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [goog.string :as gstring]
   [goog.string.format]
   [cognitect.transit :as transit]
   [cljctools.net.core.protocols :as p]
   [cljctools.net.core.spec :as core.spec]
   [cljs.nodejs :as node]))

#_(def path (node/require "fs"))
#_(def fs (node/require "path"))
(def WebSocket (node/require "ws"))

; https://github.com/websockets/ws/blob/master/doc/ws.md

(defn create-channels
  []
  (let [evt| (chan (sliding-buffer 10))
        evt|m (mult evt|)
        recv| (chan (sliding-buffer 10))
        recv|m (mult recv|)]
    {::core.spec/evt| evt|
     ::core.spec/evt|m evt|m
     ::core.spec/recv| recv|
     ::core.spec/recv|m recv|m}))

(defn create-proc-ws
  [channels ctx opts]
  (let [{:keys [::core.spec/evt| ::core.spec/recv|]} channels
        {:keys [id url]} opts
        send| (chan 10)
        socket (WebSocket. url #js {})
        w (transit/writer :json)
        r (transit/reader :json)
        transit-write (fn [data]
                        (let [out| (chan 1)
                              data-transit (transit/write w data)
                              buf (js/Buffer.from data-transit)
                              ;; blob (js/Blob. [data-transit]
                                            ;;  #js {:type "application/transit+json"})
                              ]
                          (put! out| buf)
                          #_(-> blob
                                (.arrayBuffer)
                                (.then (fn [ab]
                                         (put! out| ab))))
                          (close! out|)
                          out|))
        transit-read (fn [data]
                       (let [out| (chan 1)
                             s (.toString data)
                             d (transit/read r s)]
                         (put! out| d)
                         #_(-> blob
                               (.text)
                               (.then (fn [txt]
                                        (let [d (transit/read r txt)]
                                          (prn d)
                                          (put! out| d)))))
                         (close! out|)
                         out|))
        socket (doto socket
                 (.on "open" (fn []
                               (println "open")
                               (put! evt| {:op ::core.spec/socket-open})))
                 (.on "close" (fn [code reason]
                                (println "close" code reason)
                                (put! evt| {:op ::core.spec/socket-close
                                            :code code
                                            :reason reason})
                                ; consider using take! if using go block here is wasteful
                                ))
                 (.on "error" (fn [err]
                                (println "error" err)
                                (put! evt| {:op ::core.spec/socket-error
                                            :error err})))
                 (.on "message" (fn [data] (take! (transit-read data)
                                                  (fn [d]
                                                    (put! recv| d))))))
        state (atom {:socket socket
                     :send| send|})]
    (go
      (loop []
        (when-let [[v port] (alts! [send|])]
          (condp = port
            send| (let []
                    (take! (transit-write v)
                           (fn [d]
                             (prn d)
                             (.send socket d))))))
        (recur))
      (println "; proc-ws go-block exiting"))
    (reify
      p/Connect
      (-connect [_])
      (-disconnect [_] (.terminate socket))
      (-connected? [_] (= socket.readyState WebSocket.OPEN) #_(not socket.connecting))
      p/Send
      (-send [_ v] (put! send| v))
      cljs.core/ILookup
      (-lookup [_ k] (-lookup _ k nil))
      (-lookup [_ k not-found] (-lookup @state k not-found)))))

(defn send-data
  [_ data]
  (p/-send _ data))

(defn connected?
  [_]
  (p/-connected? _))

