(ns cljctools.vscode.chan
  #?(:cljs (:require-macros [cljctools.vscode.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.csp.op.spec :as op.spec]
   [cljctools.vscode.spec :as vscode.spec]))

(do (clojure.spec.alpha/check-asserts true))

(defmulti ^{:private true} op* op.spec/op-spec-dispatch-fn)
(s/def ::op (s/multi-spec op* op.spec/op-spec-retag-fn))
(defmulti op op.spec/op-dispatch-fn)

(defn create-channels
  []
  (let [ops| (chan 10)
        evt| (chan 10)
        evt|m (mult evt|)
        tab-send| (chan 10)
        tab-recv| (chan 10)
        tab-evt| (chan (sliding-buffer 10))
        tab-evt|m (mult tab-evt|)

        cmd| (chan (sliding-buffer 10))
        cmd|m (mult cmd|)
        ;; host|p (pub (tap host|m (chan 10)) spec/TOPIC (fn [_] 10))
        ]
    {::ops| ops|
     ::evt| evt|
     ::evt|m evt|m
     ::tab-recv| tab-recv|
     ::tab-send| tab-send|
     ::tab-evt| tab-evt|
     ::tab-evt|m tab-evt|m
     ::cmd| cmd|
     ::cmd|m cmd|m}))

(defmethod op*
  {::op.spec/op-key ::extension-activate
   ::op.spec/op-type ::op.spec/request} [_]
  (s/keys :req [:cljctools.vscode.impl/context]))


(defmethod op
  {::op.spec/op-key ::extension-activate
   ::op.spec/op-type ::op.spec/request}
  ([op-meta channels context]
   (op op-meta channels context (chan 1)))
  ([op-meta channels context out|]
   (put! (::ops| channels) (merge op-meta
                                  {:cljctools.vscode.impl/context context
                                   ::op.spec/out| out|}))
   out|))

(defmethod op*
  {::op.spec/op-key ::extension-activate
   ::op.spec/op-type ::op.spec/response} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::extension-activate
   ::op.spec/op-type ::op.spec/response}
  [op-meta out|]
  (put! out| op-meta))

(defmethod op*
  {::op.spec/op-key ::extension-deactivate} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::extension-deactivate}
  [op-meta channels]
  (put! (::ops| channels) (merge op-meta
                                 {})))

(defmethod op*
  {::op.spec/op-key ::register-commands
   ::op.spec/op-type ::op.spec/request} [_]
  (s/keys :req [::vscode.spec/cmd-ids]))

(defmethod op
  {::op.spec/op-key ::register-commands
   ::op.spec/op-type ::op.spec/request}
  ([op-meta channels cmd-ids]
   (op op-meta channels cmd-ids (chan 1)))
  ([op-meta channels cmd-ids out|]
   (put! (::ops| channels) (merge op-meta
                                  {::vscode.spec/cmd-ids cmd-ids
                                   ::op.spec/out| out|}))
   out|))

(defmethod op*
  {::op.spec/op-key ::register-commands
   ::op.spec/op-type ::op.spec/response} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::register-commands
   ::op.spec/op-type ::op.spec/response}
  [op-meta out|]
  (put! out| op-meta))


(defmethod op*
  {::op.spec/op-key ::cmd} [_]
  (s/keys :req [::vscode.spec/cmd-id]))

(defmethod op
  {::op.spec/op-key ::cmd}
  [op-meta to| cmd-id]
  (put! to| (merge op-meta
                   {::vscode.spec/cmd-id cmd-id})))


(defmethod op*
  {::op.spec/op-key ::show-info-msg} [_]
  (s/keys :req [::vscode.spec/info-msg]))

(defmethod op
  {::op.spec/op-key ::show-info-msg}
  [op-meta channels text]
  (put! (::ops| channels) (merge op-meta
                                 {::vscode.spec/info-msg text})))

(defmethod op*
  {::op.spec/op-key ::tab-create} [_]
  (s/keys :req []))

(defmethod op
  {::op.spec/op-key ::tab-create}
  [op-meta channels tab-create-opts]
  (put! (::ops| channels) (merge op-meta
                                 tab-create-opts)))

(defmethod op*
  {::op.spec/op-key ::tab-disposed} [_]
  (s/keys :req [::vscode.spec/tab-id]))

(defmethod op
  {::op.spec/op-key ::tab-disposed}
  [op-meta to| tab-id]
  (put! to| (merge op-meta
                   {::vscode.spec/tab-id tab-id})))

(defmethod op*
  {::op.spec/op-key ::tab-send} [_]
  (s/keys :req [::vscode.spec/tab-id]))

(defmethod op
  {::op.spec/op-key ::tab-send}
  [op-meta channels value tab-id]
  (put! (::tab-send| channels) (merge {::vscode.spec/tab-id tab-id} value)))


(defmethod op*
  {::op.spec/op-key ::tab-recv} [_]
  (s/keys :req [::vscode.spec/tab-id]))

(defmethod op
  {::op.spec/op-key ::tab-recv}
  [op-meta to| value tab-id]
  (put! to| (merge {::vscode.spec/tab-id tab-id} value)))


(defmethod op*
  {::op.spec/op-key ::read-dir
   ::op.spec/op-type ::op.spec/request} [_]
  (s/keys :req [::vscode.spec/dirpath]))

(defmethod op
  {::op.spec/op-key ::read-dir
   ::op.spec/op-type ::op.spec/request}
  ([op-meta channels dirpath]
   (op op-meta channels dirpath (chan 1)))
  ([op-meta channels dirpath out|]
   (put! (::ops| channels) (merge op-meta
                                  {::vscode.spec/dirpath dirpath
                                   ::op.spec/out| out|}))
   out|))


(defmethod op*
  {::op.spec/op-key ::read-dir
   ::op.spec/op-type ::op.spec/response} [_]
  (s/keys :req [::vscode.spec/filenames]))

(defmethod op
  {::op.spec/op-key ::read-dir
   ::op.spec/op-type ::op.spec/response}
  [op-meta out| filenames]
  (put! out| (merge op-meta
                    {::vscode.spec/filenames filenames})))


(defmethod op*
  {::op.spec/op-key ::read-file
   ::op.spec/op-type ::op.spec/request} [_]
  (s/keys :req [::vscode.spec/filepath]))

(defmethod op
  {::op.spec/op-key ::read-file
   ::op.spec/op-type ::op.spec/request}
  ([op-meta channels filepath]
   (op op-meta channels filepath (chan 1)))
  ([op-meta channels filepath out|]
   (put! (::ops| channels) (merge op-meta
                                  {::vscode.spec/filepath filepath
                                   ::op.spec/out| out|}))
   out|))

(defmethod op*
  {::op.spec/op-key ::read-file
   ::op.spec/op-type ::op.spec/response} [_]
  (s/keys :req [::vscode.spec/file-content]))

(defmethod op
  {::op.spec/op-key ::read-file
   ::op.spec/op-type ::op.spec/response}
  [op-meta out| file-content]
  (put! out| (merge op-meta
                    {::vscode.spec/file-content file-content})))
