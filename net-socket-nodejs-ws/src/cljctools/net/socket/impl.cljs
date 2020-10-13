(ns cljctools.net.socket.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix toggle
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cognitect.transit :as transit]
   [goog.string :refer [format]]
   [cljs.nodejs :as node]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.net.socket.spec :as socket.spec]
   [cljctools.net.socket.chan :as socket.chan]))

#_(def path (node/require "fs"))
#_(def fs (node/require "path"))
(def WebSocket (node/require "ws"))

; https://github.com/websockets/ws/blob/master/doc/ws.md

(defn create-proc-ops
  [channels opts]
  (let [{:keys [::socket.chan/ops|
                ::socket.chan/evt|m
                ::socket.chan/recv|
                ::socket.chan/send|]} channels
        send|xout (chan 10)
        send|x (mix send|xout)
        pause-sending (fn []
                        (toggle send|x {send| {:pause true}}))
        resume-sending (fn []
                         (toggle send|x {send| {:pause false}}))
        _ (do
            (admix send|x send|)
            (pause-sending))
        evt|t (tap evt|m (chan 10))
        socket (atom nil)
        state (atom opts)
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
        disconnect (fn []
                     (when-let [ws @socket]
                       (.close ws 1000 (str ::socket.chan/disconnect))
                       (reset! socket nil)))
        connect (fn []
                  (when @socket
                    (disconnect))
                  (let [ws (WebSocket. (::socket.spec/url @state) #js {})]
                    (doto ws
                      (.on "open" (fn []
                                    (println ::connected)
                                    (resume-sending)
                                    (socket.chan/op
                                     {::op.spec/op-key ::socket.chan/connected}
                                     (::socket.chan/evt| channels))))
                      (.on "close" (fn [code reason]
                                     (println ::closed)
                                     (pause-sending)
                                     (socket.chan/op
                                      {::op.spec/op-key ::socket.chan/closed}
                                      (::socket.chan/evt| channels) code reason)))
                      (.on "error" (fn [error]
                                     (println ::error)
                                     (pause-sending)
                                     (socket.chan/op
                                      {::op.spec/op-key ::socket.chan/error}
                                      (::socket.chan/evt| channels)
                                      error)))
                      (.on "message" (fn [data]
                                       (socket.chan/op
                                        {::op.spec/op-key ::socket.chan/recv}
                                        (::socket.chan/recv| channels) (transit-read data)))))
                    (reset! socket ws)))
        send (fn [v]
               (when-let [ws @socket]
                 (.send ws (transit-write v))))]
    (go
      (loop []
        (when-let [[v port] (alts! [send| ops| evt|t])]
          (condp = port
            send|
            (send v)

            ops|
            (condp = (select-keys v [::op.spec/op-key ::op.spec/op-type])

              {::op.spec/op-key  ::socket.chan/connect}
              (let []
                (swap! state merge (select-keys v [::socket.spec/url]))
                (connect))

              {::op.spec/op-key  ::socket.chan/disconnect}
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
