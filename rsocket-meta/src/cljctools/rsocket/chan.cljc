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
  (let [ops| (chan 10)]
    {::ops| ops|}))

(defmethod op*
  {::op.spec/op-key ::request-response
   ::op.spec/op-type ::op.spec/request-response
   ::op.spec/op-orient ::op.spec/request} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::request-response
   ::op.spec/op-type ::op.spec/request-response
   ::op.spec/op-orient ::op.spec/request}
  ([op-meta channels data]
   (op op-map channels data (chan 1)))
  ([op-meta channels data out|]
   (put! (::ops| channels) (merge
                            op-meta
                            data
                            {::op.spec/out| out|}))
   out|))

(defmethod op*
  {::op.spec/op-key ::request-response
   ::op.spec/op-type ::op.spec/request-response
   ::op.spec/op-orient ::op.spec/response} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::request-response
   ::op.spec/op-type ::op.spec/request-response
   ::op.spec/op-orient ::op.spec/response}
  [op-meta out| data]
  (put! out| (merge
              op-meta
              data)))



(defmethod op*
  {::op.spec/op-key ::fire-and-forget
   ::op.spec/op-type ::op.spec/fire-and-forget} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::fire-and-forget
   ::op.spec/op-type ::op.spec/fire-and-forget}
  [op-meta out| data]
  (put! out| (merge
              op-meta
              data)))




(defmethod op*
  {::rsocket.spec/op-key ::rsocket.spec/fire-and-forget} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::rsocket.spec/op-key ::rsocket.spec/fire-and-forget}
  [op-meta channels value]
  (put! (::ops| channels) (merge
                           op-meta
                           value)))


(defmethod op*
  {::rsocket.spec/op-key ::rsocket.spec/request-stream} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::rsocket.spec/op-key ::rsocket.spec/request-stream}
  ([op-meta channels value]
   (op op-meta channels value (chan 64)))
  ([op-meta channels value out|]
   (put! (::ops| channels) (merge
                            op-meta
                            value
                            {::op.spec/out| out|}))
   out|))

(defmethod op*
  {::rsocket.spec/op-key ::rsocket.spec/request-channel} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::rsocket.spec/op-key ::rsocket.spec/request-channel}
  ([op-meta channels value]
   (op op-meta channels value (chan 64) (chan 64)))
  ([op-meta channels value  out| send|]
   (put! (::ops| channels) (merge
                            op-meta
                            value
                            {::op.spec/out| out|
                             ::op.spec/send| send|}))
   {::op.spec/out| out|
    ::op.spec/send| send|}))