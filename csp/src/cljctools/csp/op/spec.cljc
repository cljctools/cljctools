(ns cljctools.csp.op.spec
  #?(:cljs (:require-macros [cljctools.csp.op.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(do (clojure.spec.alpha/check-asserts true))


(s/def ::op-key keyword?)
(s/def ::op-uuid uuid?)

(s/def ::op-type (s/nillable #{::request-response
                               ::request-stream
                               ::request-channel
                               ::fire-and-forget}))

(s/def ::val-type (s/nillable #{::request
                                ::response}))

(s/def ::op-meta (s/keys :req [::op-key ::op-type ::val-type]
                         :opt [::op-uuid]))

(s/def ::op-error any?)
(s/def ::out| some?)
(s/def ::send| some?)

(def ^:const op-meta-keys [::op-key ::op-type ::val-type])

(def op-spec-dispatch-fn (fn [value] (select-keys value op-meta-keys)))
(def op-spec-retag-fn (fn [generated-value dispatch-tag] (merge generated-value dispatch-tag)))
(def op-dispatch-fn (fn [op-meta & args] (select-keys op-meta op-meta-keys)))

(defmulti op-spec op-spec-dispatch-fn)
#_(s/def ::op (s/multi-spec op-spec op-spec-retag-fn))
(defmulti op op-dispatch-fn)