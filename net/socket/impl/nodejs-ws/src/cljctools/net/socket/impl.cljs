(ns cljctools.net.socket.api
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cognitect.transit :as transit]

   [goog.string :as gstring]
   [goog.string.format]
   [cljs.nodejs :as node]

   [cljctools.net.socket.spec :as socket.spec]
   [cljctools.net.socket.chan :as socket.chan]))

#_(def path (node/require "fs"))
#_(def fs (node/require "path"))
(def WebSocket (node/require "ws"))

; https://github.com/websockets/ws/blob/master/doc/ws.md

(defn create-proc-ops
  [channels ctx opts]
  (let [{:keys [::socket.chan/ops|m
                ::socket.chan/evt|m
                ::socket.chan/recv|
                ::socket.chan/send|m]} channels
        {:keys [::socket.spec/url]} opts
        send|t (tap send|m (chan 10))
        ops|t (tap ops|m (chan 10))
        evt|t (tap evt|m (chan 10))
        socket (atom nil)
        connect (fn []
                  (let [ws (WebSocket. url #js {})]
                    (doto ws
                      (.on "open" (fn []
                                    (println ::connected)
                                    (socket.chan/connected (::socket.chan/evt| channels))))
                      (.on "close" (fn [code reason]
                                     (println ::closed)
                                     (socket.chan/closed (::socket.chan/evt| channels) code reason)))
                      (.on "error" (fn [error]
                                     (println ::error)
                                     (socket.chan/error (::socket.chan/evt| channels) error)))
                      (.on "message" (fn [data]
                                       (socket.chan/recv (::socket.chan/recv| channels) (transit-read data)))))
                    (reset! socket ws)))
        disconnect (fn []
                     (when-let [ws @socket]
                       (.close ws 0 (str ::socket.chan/disconnect))))
        transit-writer (transit/writer :json)
        transit-reader (transit/reader :json)
        transit-write (fn [data]
                        (let [value (transit/write transit-writer data)
                              value-buf (js/Buffer.from value)]
                          value-buf))
        transit-read (fn [data]
                       (let [value-str (.toString data)
                             value (transit/read transit-reader value-str)]
                         value))
        send (fn [v]
               (when-let [ws @socket]
                 (.send ws (transit-write v))))]
    (go
      (loop []
        (when-let [[v port] (alts! [send|t ops|t evt|t])]
          (condp = port
            send|t
            (send v)

            ops|t
            (condp = (:op v)
              ::socket.chan/connect
              (let []
                (connect))

              ::socket.chan/disconnect
              (let []
                (disconnect)))
            
            evt|t
            (do nil)))
        (recur))
      (println (format "go-block exit %s" ::create-proc-ops)))
    #_(reify
        p/Connect
        (-connect [_])
        (-disconnect [_] (.terminate socket))
        (-connected? [_] (= socket.readyState WebSocket.OPEN) #_(not socket.connecting))
        p/Send
        (-send [_ v] (put! send| v))
        cljs.core/ILookup
        (-lookup [_ k] (-lookup _ k nil))
        (-lookup [_ k not-found] (-lookup @state k not-found)))))
