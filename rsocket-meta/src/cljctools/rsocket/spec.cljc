(ns cljctools.rsocket.spec
  #?(:cljs (:require-macros [cljctools.rsocket.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::url string?)

(s/def ::op-key {::request-response ::fire-and-forget ::request-stream ::request-channel})

(derive ::request-response ::op-key)
(derive ::fire-and-forget ::op-key)
(derive ::request-stream ::op-key)
(derive ::request-channel ::op-key)

(comment
  ;https://clojure.org/reference/multimethods

  (isa? ::request-response ::op-key)

  (isa? {::op-key ::request-response} {::op-key ::op-key})
  (isa? [::op-key ::request-response] [::op-key ::op-key])
  
  (vec {:a :b :c :d})
  (isa? [[:a [::op-key ::request-response]]] [[:a [::op-key ::op-key]]])
  
  

  ;;
  )

(s/def ::host string?)
(s/def ::port int?)
(s/def ::path string?)

(s/def ::num-code int?)
(s/def ::reason-text string?)
(s/def ::error any?)
(s/def ::reconnection-timeout int?)