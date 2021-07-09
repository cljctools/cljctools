(ns cljctools.nrepl.client.chan
  #?(:cljs (:require-macros [cljctools.nrepl.client.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.csp.op.spec :as op.spec]
   [cljctools.nrepl.client.spec :as nrepl.client.spec]))

(do (clojure.spec.alpha/check-asserts true))

(defmulti ^{:private true} op* op.spec/op-spec-dispatch-fn)
(s/def ::op (s/multi-spec op* op.spec/op-spec-retag-fn))
(defmulti op op.spec/op-dispatch-fn)

(defn create-channels
  []
  (let [ops| (chan 10)]
    {::ops| ops|}))


(defmethod op*
  {::op.spec/op-key ::eval
   ::op.spec/op-type ::op.spec/request} [_]
  (s/keys :req [::op.spec/out|
                ::nrepl.client.spec/code
                ::nrepl.client.spec/session]))

(defmethod op
  {::op.spec/op-key ::eval
   ::op.spec/op-type ::op.spec/request}
  ([op-meta channels opts]
   (op op-meta channels opts (chan 1)))
  ([op-meta channels opts out|]
   (put! (::ops| channels)
         (merge op-meta
                {::op.spec/out| out|}))
   out|))


(defmethod op*
  {::op.spec/op-key ::eval
   ::op.spec/op-type ::op.spec/response} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::eval
   ::op.spec/op-type ::op.spec/response}
  [op-meta out| value]
  (put! out|
        (merge op-meta
               value)))


(defmethod op*
  {::op.spec/op-key ::clone-session
   ::op.spec/op-type ::op.spec/request} [_]
  (s/keys :req [::op.spec/out|
                ::nrepl.client.spec/code
                ::nrepl.client.spec/session]))

(defmethod op
  {::op.spec/op-key ::clone-session
   ::op.spec/op-type ::op.spec/request}
  ([op-meta channels opts]
   (op op-meta channels opts (chan 1)))
  ([op-meta channels opts out|]
   (put! (::ops| channels)
         (merge op-meta
                {::op.spec/out| out|}))
   out|))

(defmethod op*
  {::op.spec/op-key ::clone-session
   ::op.spec/op-type ::op.spec/response} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::clone-session
   ::op.spec/op-type ::op.spec/response}
  [op-meta out| value]
  (put! out|
        (merge op-meta
               value)))
