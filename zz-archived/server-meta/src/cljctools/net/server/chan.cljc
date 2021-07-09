(ns cljctools.net.server.chan
  #?(:cljs (:require-macros [cljctools.net.server.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.csp.op.spec :as op.spec]
   [cljctools.net.server.spec :as server.spec]))

(defmulti ^{:private true} op* op.spec/op-spec-dispatch-fn)
(s/def ::op (s/multi-spec op* op.spec/op-spec-retag-fn))
(defmulti op op.spec/op-dispatch-fn)

(defn create-channels
  []
  (let [ops| (chan 10)
        ws-evt| (chan (sliding-buffer 50))
        ws-evt|m (mult ws-evt|)
        ws-recv| (chan (sliding-buffer 50))
        ws-recv|m (mult ws-recv|)]
    {::ops| ops|
     ::ws-evt| ws-evt|
     ::ws-evt|m ws-evt|m
     ::ws-recv| ws-recv|
     ::ws-recv|m ws-recv|m}))

(defmethod op*
  {::op.spec/op-key ::start-server} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::start-server}
  [op-meta channels]
  (put! (::ops| channels) op-meta))

(defmethod op*
  {::op.spec/op-key ::stop-server} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::stop-server}
  [op-meta channels]
  (put! (::ops| channels) op-meta))

(defmethod op*
  {::op.spec/op-key ::ws-connected} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::ws-connected}
  [op-meta to|]
  (put! to|
        (merge op-meta
               {})))

(defmethod op*
  {::op.spec/op-key ::ws-closed} [_]
  (s/keys :req [::server.spec/num-code ::server.spec/reason-text]
          :req-un []))

(defmethod op
  {::op.spec/op-key ::ws-closed}
  [op-meta to| num-code reason-text]
  (put! to|
        (merge op-meta
               {::server.spec/num-code num-code
                ::server.spec/reason-text reason-text})))

(defmethod op*
  {::op.spec/op-key ::ws-error} [_]
  (s/keys :req [::server.spec/error]
          :req-un []))

(defmethod op
  {::op.spec/op-key ::ws-error}
  [op-meta to| error]
  (put! to|
        (merge op-meta
               {::server.spec/error error})))

(defmethod op*
  {::op.spec/op-key ::ws-recv} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::ws-recv}
  [op-meta to| value]
  (put! to| value))
