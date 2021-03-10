(ns cljctools.socket
  (:refer-clojure :exclude [send])
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   #?(:cljs [cljs.reader :refer [read-string]])
   #?(:cljs [goog.string.format])
   #?(:cljs [goog.string :refer [format]])
   [clojure.spec.alpha :as s]))

(s/def ::num-code int?)
(s/def ::reason-text string?)
(s/def ::error any?)

(s/def ::reconnection-timeout int?)
(s/def ::connect-fn ifn?)
(s/def ::disconnect-fn ifn?)
(s/def ::send-fn ifn?)

(s/def ::connected keyword?)
(s/def ::ready keyword?)
(s/def ::timeout keyword?)
(s/def ::closed keyword?)
(s/def ::error keyword?)
(s/def ::raw-socket some?)

(defprotocol Socket
  (connect* [_])
  (disconnect* [_])
  (close* [_])
  (send* [_ data] "data is passed directly to ::send-fn"))

(s/def ::socket #(satisfies? Socket %))

(defonce ^:private registryA (atom {}))

(defn open
  "Creates and connects a socket if one does not exist.
   Returns Socket instance or already existing instance
   Socket instaces are also stored in a global registry atom by ::id for convinience: can do close,send by simply provding id.
   (def socket (cljctools.socket/open {:cljctools.socket/id ::foo })).
   Can (close socket) or (close ::foo), (send socket data) or (send ::foo data).
   Can be derefernced  to get current state - @socket simply returns underlying atom's state.
   For ::connect-fn ::disconnect-fn ::send-fn see cljctools.socket.nodejs_net, cljctools.socket.websocket implementations"
  [{:as opts
    :keys [::id
           ::send|
           ::recv|
           ::evt|
           ::evt|mult
           ::connect-fn
           ::disconnect-fn
           ::reconnection-timeout
           ::send-fn]
    :or {id (str #?(:clj  (java.util.UUID/randomUUID)
                    :cljs (random-uuid)))
         reconnection-timeout 1000
         send| (chan (sliding-buffer 10))
         recv| (chan (sliding-buffer 10))
         evt| (chan (sliding-buffer 10))}}]
  (or
   (get @registryA id)
   (let [evt|mult (or evt|mult (mult evt|))
         evt|tap (tap evt|mult (chan (sliding-buffer 10)))
         stateA (atom (merge
                       opts
                       {::opts opts
                        ::send| send|
                        ::evt| evt|
                        ::evt|mult evt|mult
                        ::raw-socket nil
                        ::recv| recv|}))
         socket
         ^{:type ::socket}
         (reify
           Socket
           (connect* [_]
             (when (get @stateA ::raw-socket)
               (disconnect* _))
             (swap! stateA assoc ::raw-socket (<! (connect-fn @stateA))))
           (disconnect* [_]
             (when (get @stateA ::raw-socket)
               (disconnect-fn @stateA)
               (swap! stateA dissoc ::raw-socket)))
           (send* [_ data]
             (when (get @stateA ::raw-socket)
               (send-fn @stateA data)))
           (close* [_]
             (disconnect* _)
             (untap evt|mult evt|tap)
             (close! evt|tap))
           #?(:clj clojure.lang.IDeref)
           #?(:clj (deref [_] @stateA))
           #?(:cljs cljs.core/IDeref)
           #?(:cljs (-deref [_] @stateA)))]
     (swap! registryA assoc id stateA)
     (connect* socket)
     (go
       (loop []
         (let [[value port] (alts! [send| evt|tap])]
           (when value
             (condp = port

               send|
               (send* socket value)

               evt|tap
               (condp = (:op value)

                 ::closed
                 (let []
                   (when reconnection-timeout
                     (<! (timeout reconnection-timeout))
                     (connect* socket)))
                 (do nil)))
             (recur)))))
     socket)))

(defmulti close
  "Closes the socket"
  {:arglists '([id] [socket])} type)
(defmethod close :default
  [id]
  (when-let [socket (get @registryA id)]
    (close socket)))
(defmethod close ::socket
  [socket]
  (close* socket)
  (swap! registryA dissoc (get @socket ::id)))

(defmulti send
  "Send data, data is passed directly to the underlying socket.
   See for example ::send-fn implmentation of cljctools.socket.nodejs_net."
  {:arglists '([id data] [socket data])} type)
(defmethod send :default
  [id data]
  (when-let [socket (get @registryA id)]
    (send socket)))
(defmethod send ::socket
  [socket data]
  (send* socket data))

(comment

  (do
    (def stateA (atom {:x 1}))

    (defprotocol Socket
      (disconnect* [_])
      (send* [_ data]))

    (def x
      ^{:type ::foo}
      (reify
        Socket
        (disconnect* [_] (throw (ex-info "foo" {})))
        (send* [_ data])
        #?(:clj clojure.lang.IDeref)
        #?(:clj (deref [_] @stateA))
        #?(:cljs cljs.core/IDeref)
        #?(:cljs (-deref [_] @stateA))))


    [(= @x @stateA)
     (satisfies? Socket x)
     (type Socket)
     Socket
     (type x)]
    ;; => true
    )

  ;;
  )