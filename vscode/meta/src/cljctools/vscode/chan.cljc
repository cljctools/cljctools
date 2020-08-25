(ns cljctools.vscode.chan
  #?(:cljs (:require-macros [cljctools.vscode.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.vscode.spec :as vscode.spec]))

(do (clojure.spec.alpha/check-asserts true))

(s/def ::out| some?)
(s/def ::value some?)

(s/def ::extension-activate (s/keys :req [::vscode.spec/context] :req-un [::out|]))
(s/def ::extension-deactivate (s/keys :req [] :req-un [::out|]))
(s/def ::register-commands (s/keys :req [::vscode.spec/cmd-ids] :req-un [::out|]))
(s/def ::extension-cmd (s/keys :req [::vscode.spec/cmd-id]))
(s/def ::show-info-msg (s/keys :req [::vscode.spec/info-msg]))

(s/def ::tab-create (s/keys :req []))
(s/def ::tab-disposed (s/keys :req [::vscode.spec/tab-id]))


(defn create-channels
  []
  (let [ops| (chan 10)
        ops|m (mult ops|)
        evt| (chan 10)
        evt|m (mult evt|)
        evt|x (mix evt|)
        tab-send| (chan 10)
        tab-send|m (mult tab-send|)
        tab-recv| (chan 10)
        tab-recv|m (mult tab-recv|)
        tab-evt| (chan (sliding-buffer 10))
        tab-evt|m (mult tab-evt|)

        cmd| (chan (sliding-buffer 10))
        cmd|m  (mult cmd|)
        ;; host|p (pub (tap host|m (chan 10)) spec/TOPIC (fn [_] 10))
        ]
    {::ops| ops|
     ::ops|m ops|m
     ::evt| evt|
     ::evt|m evt|m
     ::evt|x evt|x
     ::tab-recv| tab-recv|
     ::tab-recv|m tab-recv|m
     ::tab-send| tab-send|
     ::tab-send|m tab-send|m
     ::tab-evt| tab-evt|
     ::tab-evt|m tab-evt|m
     ::cmd| cmd|
     ::cmd|m cmd|m}))


(defn extension-activate
  ([channels context]
   (extension-activate channels context (chan 1)))
  ([{:keys [::ops|] :as channels} context out|]
   (put! ops| {:op ::extension-activate :cljctools.vscode.impl/context context :out| out|})
   out|))

(defn extension-deactivate
  ([channels context]
   (extension-deactivate channels context (chan 1)))
  ([{:keys [::ops|] :as channels} context out|]
   (put! ops| {:op ::extension-deactivate :out| out|})
   out|))

(defn tab-create
  ([channels opts]
   (tab-create channels opts (chan 1)))
  ([channels opts out|]
   (put! (::ops| channels) (merge {:op ::tab-create :out| out|} opts))
   out|))

(defn tab-recv
  [to| value tab-id]
  (put! to| (merge {::vscode.spec/tab-id tab-id} value)))

(defn tab-send
  [channels value tab-id]
  (put! (::tab-send| channels) (merge {::vscode.spec/tab-id tab-id} value)))

(defn tab-disposed
  [to| tab-id]
  (put! to| {:op ::tab-disposed ::vscode.spec/tab-id tab-id}))

(defn register-commands
  ([channels cmd-ids]
   (register-commands channels cmd-ids (chan 1)))
  ([channels cmd-ids out|]
   (put! (::ops| channels) {:op ::register-commands ::vscode.spec/cmd-ids cmd-ids :out| out|})
   out|))

(defn extension-cmd
  [to| cmd-id]
  (put! to| {:op ::extension-cmd ::vscode.spec/cmd-id cmd-id}))

(defn show-info-msg
  [channels text]
  (put! (::ops| channels) {:op ::show-info-msg ::vscode.spec/info-msg text}))

(comment
  (def ^:const OP :op)
  (s/def ::out| any?)
  (def ^:const TOPIC :topic)

  (def op-specs
    {::extension-activate (s/keys :req-un [::op #_::out|])
     ::extension-deactivate (s/keys :req-un [::op #_::out|])
     ::tab-disposed (s/keys :req-un [::op #_::out|])
     ::cmd (s/keys :req-un [::op #_::out|])})

  (def ch-specs
    {::ops| #{::extension-activate
              ::extension-deactivate}
     ::evt| #{::extension-activate
              ::extension-deactivate}
     ::tab-state| #{::tab-disposed}
     ::cmd| #{::cmd}})

  (def op-keys (set (keys op-specs)))
  (def ch-keys (set (keys ch-specs)))

  (s/def ::op op-keys)

  (s/def ::ch-exists ch-keys)
  (s/def ::op-exists (fn [v] (op-keys (if (keyword? v) v (OP v)))))
  (s/def ::ops-exist (fn [v] (subset? (set v) op-keys)))
  (s/def ::ch-op-exists (s/cat :ch ::ch-exists :op ::op-exists))


  (defmacro op
    [chkey opkey]
    (s/assert ::ch-exists  chkey)
    (s/assert ::op-exists  opkey)
    `~opkey)

  (defmacro ops
    [ops]
    (s/assert ::ops-exist  ops)
    `~ops)


  (defmacro vl
    [chkey v]
    (s/assert ::ch-exists  chkey)
    (when-not (symbol? (OP v))
      (s/assert ::op-exists  (OP v)))
    `~v)

  ;;
  )