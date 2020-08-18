(ns cljctools.vscode.spec
  #?(:cljs
     (:require-macros [cljctools.vscode.spec]))
  (:require
   [clojure.spec.alpha :as s]
   [clojure.set :refer [subset?]]
   [clojure.test.check.generators :as gen]))

(do (clojure.spec.alpha/check-asserts true))

(def ^:const OP :op)
(s/def ::out| any?)
(def ^:const TOPIC :topic)

(def op-specs
  {:host/extension-activate (s/keys :req-un [::op #_::out|])
   :host/extension-deactivate (s/keys :req-un [::op #_::out|])
   :host/tab-disposed (s/keys :req-un [::op #_::out|])
   :host/cmd (s/keys :req-un [::op #_::out|])
   })

(def ch-specs
  {:host-ops| #{:host/extension-activate
                :host/extension-deactivate}
   :host-evt| #{:host/extension-activate
                :host/extension-deactivate}
   :tab-state| #{:host/tab-disposed}
   :host-cmd| #{:host/cmd}})

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
