(ns cljctools.nrepl.server.chan
  #?(:cljs (:require-macros [cljctools.nrepl.server.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.csp.op.spec :as op.spec]
   [cljctools.nrepl.server.spec :as nrepl.server.spec]))

(do (clojure.spec.alpha/check-asserts true))

(defmulti ^{:private true} op* op.spec/op-spec-dispatch-fn)
(s/def ::op (s/multi-spec op* op.spec/op-spec-retag-fn))
(defmulti op op.spec/op-dispatch-fn)

(defn create-channels
  []
  (let [ops| (chan 10)
        nrepl-enter| (chan (sliding-buffer 10))
        nrepl-leave| (chan (sliding-buffer 10))]
    {::ops| ops|
     ::nrepl-enter| nrepl-enter|
     ::nrepl-leave| nrepl-leave|}))

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