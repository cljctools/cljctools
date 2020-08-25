(ns cljctools.net.socket.chan
  (:refer-clojure :exclude [send])
  #?(:cljs (:require-macros [cljctools.net.socket.chan]))
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.net.socket.spec :as socket.spec]))

(do (clojure.spec.alpha/check-asserts true))

(s/def ::connect (s/keys :req []))
(s/def ::disconnect (s/keys :req []))

(s/def ::connected (s/keys :req []))
(s/def ::closed (s/keys :req [::socket.spec/num-code ::socket.spec/reason-text]))
(s/def ::error (s/keys :req [] :req-un [::error]))

(defn create-channels
  []
  (let [send| (chan 10)
        send|m (mult send|)
        recv| (chan (sliding-buffer 10))
        recv|m (mult recv|)
        evt| (chan (sliding-buffer 10))
        evt|m (mult evt|)]
    {:send| send|
     :send|m send|m
     :recv| recv|
     :recv|m recv|m
     :evt| evt|
     :evt|m evt|m}))

(defn connected
  [to|]
  (put! to| {:op ::connected}))

(defn closed
  [to| num-code reason-text]
  (put! to| {:op ::closed ::socket.spec/num-code num-code ::socket.spec/reason-text reason-text}))

(defn error
  [to| error]
  (put! to| {:op ::error :error error}))

(defn recv
  [to| value]
  (put! to| value))

(defn connect
  [channels]
  (put! channels {:op ::connect}))

(defn disconnect
  [channels]
  (put! channels {:op ::disconnect}))

(defn send
  [channels value]
  (put! (::send| channels) value))


(comment

  (do (clojure.spec.alpha/check-asserts true))

  (def ^:const OP :op)
  (s/def ::out| any?)

  (def op-specs
    {:hello (s/keys :req-un [::op #_::out|])})

  (def ch-specs
    {:some| #{:hello}})

  (def op-keys (set (keys op-specs)))
  (def ch-keys (set (keys ch-specs)))


  (s/def ::op op-keys)

  (s/def ::ch-exists ch-keys)
  (s/def ::op-exists (fn [v] (op-keys (if (keyword? v) v (OP v)))))
  (s/def ::ch-op-exists (s/cat :ch ::ch-exists :op ::op-exists))

  (defmacro op
    [chkey opkey]
    (s/assert ::ch-exists  chkey)
    (s/assert ::op-exists  opkey)
    `~opkey)

  (defmacro vl
    [chkey v]
    (s/assert ::ch-exists  chkey)
    `~v)
  

  ;;
  )