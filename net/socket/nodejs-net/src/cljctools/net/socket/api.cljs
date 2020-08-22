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
   [cljs.nodejs :as node]))

#_(def path (node/require "fs"))
#_(def fs (node/require "path"))
(def net (node/require "net"))


(defn create-channels
  []
  (let [ws-evt| (chan (sliding-buffer 10))
        ws-evt|m (mult ws-evt|)
        ws-recv| (chan (sliding-buffer 10))
        ws-recv|m (mult ws-recv|)]
    {:ws-evt| ws-evt|
     :ws-evt|m ws-evt|m
     :ws-recv| ws-recv|
     :ws-recv|m ws-recv|m}))

(defn create-proc-ws
  [channels ctx opts]
  (let [{:keys [ws-evt| ws-recv|]} channels
        {:keys [reconnection-timeout id]
         :or {reconnection-timeout 1000000000}} opts
        send| (chan 10)
        socket (net.Socket.)
        connect #(.connect socket (clj->js (select-keys opts [:host :port :path])))
        w (transit/writer :json)
        r (transit/reader :json)
        transit-write (fn [data]
                        (let [out| (chan 1)
                              data-transit (transit/write w data)
                              blob (js/Blob. [data-transit] #js {:type "application/transit+json"})]
                          (-> blob
                              (.arrayBuffer)
                              (.then (fn [ab]
                                       (put! out| ab))))
                          (close! out|)
                          out|))
        transit-read (fn [data]
                       (let [out| (chan 1)
                             blob data]
                         (-> blob
                             (.text)
                             (.then (fn [txt]
                                      (let [d (transit/read r txt)]
                                        (prn d)
                                        (put! out| d)))))
                         (close! out|)
                         out|))
        socket (doto socket
                 (.on "connect" (fn []
                                  (println "connect")
                                  (put! ws-evt| {:op :ws/connected})))
                 (.on "ready" (fn []
                                (println "ready" )
                                (put! ws-evt| {:op :ws/ready})))
                 (.on "timeout" (fn [] (put! ws-evt| {:op :ws/timeout})
                                  (println "timeout")))
                 (.on "close" (fn [hadError]
                                (println "close" hadError)
                                (when (and (not socket.connecting) (not socket.pending))
                                  (put! ws-evt| {:op :ws/close :hadError hadError}))
                                ; consider using take! if using go block here is wasteful
                                (go
                                  (<! (timeout reconnection-timeout))
                                  (cond
                                    socket.connecting (do nil)
                                    socket.pending (do (connect))
                                    :else (do nil)))))
                 (.on "error" (fn [err]
                                (println "error" err)
                                (put! ws-evt| {:op :ws/error :error err})))
                 (.on "data" (fn [buf] (take! (transit-read buf)
                                              (fn [d]
                                                (put! ws-recv| d))))))
        state (atom {:socket socket
                     :send| send|})]
    (do (connect))
    (go
      (loop []
        (when-let [[v port] (alts! [send|])]
          (condp = port
            send| (let []
                    (take! (transit-write v)
                           (fn [d]
                             (.write socket d))))))
        (recur))
      (println "; proc-ws go-block exiting"))
    (reify
      p/Connect
      (-connect [_] (connect))
      (-disconnect [_] (.end socket))
      (-connected? [_] (not socket.pending) #_(not socket.connecting))
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

#_(defn netsocket
    [opts]
    (let [{:keys [reconnection-timeout id]
           :or {reconnection-timeout 1000}} opts
          status| (chan (sliding-buffer 10))
          send| (chan (sliding-buffer 1))
          receive| (chan (sliding-buffer 10))
          receive|m (mult receive|)
          netsock|i (channels/netsock|i)
          socket (net.Socket.)
          connect #(.connect socket (clj->js (select-keys opts [:host :port])))
          socket (doto socket
                   (.on "connect" (fn [] (put! status| (p/-vl-connected netsock|i opts))))
                   (.on "ready" (fn [] (put! status| (p/-vl-ready netsock|i opts))))
                   (.on "timeout" (fn [] (put! status| (p/-vl-timeout netsock|i  opts))))
                   (.on "close" (fn [hadError]
                                  (when (and (not socket.connecting) (not socket.pending))
                                    (put! status| (p/-vl-disconnected netsock|i hadError opts)))
                                ; consider using take! if using go block here is wasteful
                                  (go
                                    (<! (timeout reconnection-timeout))
                                    (cond
                                      socket.connecting (do nil)
                                      socket.pending (do (connect))
                                      :else (do nil)))))
                   (.on "error" (fn [err] (put! status| (p/-vl-error netsock|i err opts))))
                   (.on "data" (fn [buf] (put! receive| buf))))
          lookup (merge opts {:status| status|
                              :send| send|
                              :receive|m receive|m})
          conn (reify
                 cljs.core/ILookup
                 (-lookup [_ k] (-lookup _ k nil))
                 (-lookup [_ k not-found] (-lookup lookup k not-found))
                 p/Connect
                 (-connect [_] (connect))
                 (-disconnect [_] (.end socket))
                 (-connected? [_] (not socket.pending) #_(not socket.connecting))
                 p/Send
                 (-send [_ v] (.write socket v))
                 p/Release
                 (-release [_] (close! send|)))
          release #(do
                     (p/-disconnect conn)
                     (close! send|)
                     (close! receive|)
                     (close! status|))]
      (go-loop []
        (when-let [v (<! send|)]
          (p/-send conn v)
          (recur))
        (release))
      conn))


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