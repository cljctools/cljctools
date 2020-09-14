(ns cljctools.vscode.tab-conn.chan
  #?(:cljs (:require-macros [cljctools.vscode.tab-conn.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljctools.csp.op.spec :as op.spec]
   [clojure.spec.alpha :as s]))

(do (clojure.spec.alpha/check-asserts true))

(defmulti ^{:private true} op* op.spec/op-spec-dispatch-fn)
(s/def ::op (s/multi-spec op* op.spec/op-spec-retag-fn))
(defmulti op op.spec/op-dispatch-fn)

(defn create-channels
  []
  (let [recv| (chan (sliding-buffer 10))
        recv|m (mult recv|)
        send| (chan 10)
        send|m (mult send|)]
    {::recv| recv|
     ::recv|m recv|m
     ::send| send|
     ::send|m send|m}))


(defmethod op*
  {::op.spec/op-key ::tab-send} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::tab-send}
  [op-meta channels value]
  (put! (::tab-send| channels) value))


(defmethod op*
  {::op.spec/op-key ::tab-recv} [_]
  (s/keys :req []
          :req-un []))

(defmethod op
  {::op.spec/op-key ::tab-recv}
  [op-meta to| value]
  (put! to| value))

