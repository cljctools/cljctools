(ns cljctools.rsocket.chan
  #?(:cljs (:require-macros [cljctools.rsocket.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.csp.op.spec :as op.spec]
   [cljctools.rsocket.spec :as rsocket.spec]))

(do (clojure.spec.alpha/check-asserts true))

(defmulti ^{:private true} op* op.spec/op-spec-dispatch-fn)
(s/def ::op (s/multi-spec op* op.spec/op-spec-retag-fn))
(defmulti op op.spec/op-dispatch-fn)

(defn create-channels
  []
  (let [ops| (chan 10)
        send| (chan (dropping-buffer 1024))
        recv| (chan (sliding-buffer 10))
        evt| (chan (sliding-buffer 10))
        evt|m (mult evt|)]
    {::ops| ops|
     ::send| send|
     ::recv| recv|
     ::evt| evt|
     ::evt|m evt|m}))

(defmethod op*
  {::op.spec/op-key ::send} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::send}
  [op-meta channels value]
  (put! (::send| channels) value))

(defmethod op*
  {::op.spec/op-key ::recv} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::recv}
  [op-meta to| value]
  (put! to| value))


(defmethod op*
  {::op.spec/op-key ::connect} [_]
  (s/keys :req []
          :req-un []
          :opt [::rsocket.spec/url
                ::rsocket.spec/host
                ::rsocket.spec/port
                ::rsocket.spec/path]))

(defmethod op
  {::op.spec/op-key ::connect}
  ([op-meta channels]
   (op op-meta channels nil))
  ([op-meta channels opts]
   (put! (::ops| channels)
         (merge op-meta
                opts))))

(defmethod op*
  {::op.spec/op-key ::disconnect} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::disconnect}
  [op-meta channels]
  (put! (::ops| channels)
        (merge op-meta
               {})))

(defmethod op*
  {::op.spec/op-key ::connected} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::connected}
  [op-meta to|]
  (put! to|
        (merge op-meta
               {})))

(defmethod op*
  {::op.spec/op-key ::closed} [_]
  (s/keys :req [::rsocket.spec/num-code ::rsocket.spec/reason-text]
          :req-un []))

(defmethod op
  {::op.spec/op-key ::closed}
  [op-meta to| num-code reason-text]
  (put! to|
        (merge op-meta
               {::rsocket.spec/num-code num-code
                ::rsocket.spec/reason-text reason-text})))

(defmethod op*
  {::op.spec/op-key ::error} [_]
  (s/keys :req [::rsocket.spec/error]
          :req-un []))

(defmethod op
  {::op.spec/op-key ::error}
  [op-meta to| error]
  (put! to|
        (merge op-meta
               {::rsocket.spec/error error})))


(defmethod op*
  {::op.spec/op-key ::timeout} [_]
  (s/keys :req [::rsocket.spec/timeout]
          :req-un []))

(defmethod op
  {::op.spec/op-key ::timeout}
  [op-meta to|]
  (put! to|
        (merge op-meta)))

(defmethod op*
  {::op.spec/op-key ::ready} [_]
  (s/keys :req [::rsocket.spec/ready]
          :req-un []))

(defmethod op
  {::op.spec/op-key ::ready}
  [op-meta to|]
  (put! to|
        (merge op-meta)))