(ns cljctools.csp.op.spec
  #?(:cljs (:require-macros [cljctools.csp.op.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(do (clojure.spec.alpha/check-asserts true))


(s/def ::op-key keyword?)
(s/def ::op-uuid uuid?)

(s/def ::op-type (s/nillable #{::request-response
                               ::fire-and-forget
                               ::request-stream
                               ::request-channel}))

(s/def ::op-orient (s/nillable #{::request
                               ::response}))

(s/def ::op-data any?)

(s/def ::op-map (s/keys :req [::op-key ::op-type ::op-orient]
                        :opt [::op-uuid ::op-data]))


(s/def ::op-error any?)
(s/def ::out| some?)
(s/def ::send| some?)

(def ^:const op-dispatch-keys [::op-key ::op-type ::op-orient])

(def op-spec-dispatch-fn (fn [op-map] (select-keys op-map op-dispatch-keys)))
(def op-spec-retag-fn (fn [generated-value dispatch-tag] (merge generated-value dispatch-tag)))
(def op-dispatch-fn (fn [op-map & args] (select-keys op-map op-dispatch-keys)))

(defmulti op-spec op-spec-dispatch-fn)
#_(s/def ::op (s/multi-spec op-spec op-spec-retag-fn))
(defmulti op op-dispatch-fn)

(comment
  ;https://clojure.org/reference/multimethods

  (derive ::request-response ::op-key)
  (derive ::fire-and-forget ::op-key)
  (derive ::request-stream ::op-key)
  (derive ::request-channel ::op-key)


  (isa? ::request-response ::op-key)

  (isa? {::op-key ::request-response} {::op-key ::op-key})
  (isa? [::op-key ::request-response] [::op-key ::op-key])

  (vec {:a :b :c :d})
  (isa? [[:a [::op-key ::request-response]]] [[:a [::op-key ::op-key]]])

  ;;
  )