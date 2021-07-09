(ns cljctools.socket.core
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
   [clojure.spec.alpha :as s]
   [cljctools.socket.protocols :as socket.protocols]
   [cljctools.socket.spec :as socket.spec]))

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
    :keys [::socket.spec/id
           ::socket.spec/send|
           ::socket.spec/recv|
           ::socket.spec/recv|mult
           ::socket.spec/evt|
           ::socket.spec/evt|mult
           ::socket.spec/connect-fn
           ::socket.spec/disconnect-fn
           ::socket.spec/send-fn
           ::socket.spec/reconnection-timeout
           ::socket.spec/connect?]
    :or {id (str #?(:clj  (java.util.UUID/randomUUID)
                    :cljs (random-uuid)))
         reconnection-timeout 1000
         send| (chan (sliding-buffer 10))
         recv| (chan (sliding-buffer 10))
         evt| (chan (sliding-buffer 10))
         connect? true}}]
  {:pre [(s/assert ::socket.spec/opts opts)]
   :post [(s/assert ::socket.spec/socket %)]}
  (or
   (get @registryA id)
   (let [evt|mult (or evt|mult (mult evt|))
         recv|mult (or recv|mult (mult recv|))
         evt|tap (tap evt|mult (chan (sliding-buffer 10)))
         stateA (atom nil)
         socket
         ^{:type ::socket.spec/socket}
         (reify
           socket.protocols/Socket
           (connect* [_]
             (when (get @stateA ::socket.spec/raw-socket)
               (socket.protocols/disconnect* _))
             (swap! stateA assoc ::socket.spec/raw-socket (connect-fn _)))
           (disconnect* [_]
             (when (get @stateA ::socket.spec/raw-socket)
               (disconnect-fn _)
               (swap! stateA dissoc ::socket.spec/raw-socket)))
           (send* [_ data]
             (when (get @stateA ::socket.spec/raw-socket)
               (send-fn _ data)))
           (close* [_]
             (socket.protocols/disconnect* _)
             (untap evt|mult evt|tap)
             (close! evt|tap))
           #?(:clj clojure.lang.IDeref)
           #?(:clj (deref [_] @stateA))
           #?(:cljs cljs.core/IDeref)
           #?(:cljs (-deref [_] @stateA)))]
     (reset! stateA (merge
                     opts
                     {::socket.spec/opts opts
                      ::socket.spec/send| send|
                      ::socket.spec/evt| evt|
                      ::socket.spec/evt|mult evt|mult
                      ::socket.spec/raw-socket nil
                      ::socket.spec/recv| recv|
                      ::socket.spec/recv|mult recv|mult}))
     (when connect?
       (socket.protocols/connect* socket))
     (go
       (loop []
         (let [[value port] (alts! [send| evt|tap])]
           (when value
             (condp = port

               send|
               (socket.protocols/send* socket value)

               evt|tap
               (condp = (:op value)

                 ::socket.spec/closed
                 (let []
                   (when reconnection-timeout
                     (<! (timeout reconnection-timeout))
                     (socket.protocols/connect* socket)))
                 (do nil)))
             (recur)))))
     (swap! registryA assoc id socket)
     socket)))


(defmulti close
  "Closes the socket"
  {:arglists '([id] [socket])} type)
(defmethod close :default
  [id]
  (when-let [socket (get @registryA id)]
    (close socket)))
(defmethod close ::socket.spec/socket
  [socket]
  {:pre [(s/assert ::socket.spec/socket socket)]}
  (socket.protocols/close* socket)
  (swap! registryA dissoc (get @socket ::socket.spec/id)))


(defmulti send
  "Send data, data is passed directly to the underlying socket.
   See for example ::send-fn implmentation of cljctools.socket.nodejs_net."
  {:arglists '([id data] [socket data])} type)
(defmethod send :default
  [id data]
  (when-let [socket (get @registryA id)]
    (send socket)))
(defmethod send ::socket.spec/socket
  [socket data]
  {:pre [(s/assert ::socket.spec/socket socket)]}
  (socket.protocols/send* socket data))


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

(comment

  (do
    (clojure.spec.alpha/check-asserts true)
    (defmulti foo identity)
    (defmethod foo ::baz
      [id]
      {:pre [(clojure.spec.alpha/assert string? id)]
       :post [(string? %)]}
      (str id))
    (foo ::baz))

  (clojure.spec.alpha/explain keyword? :bax)


  ;;
  )