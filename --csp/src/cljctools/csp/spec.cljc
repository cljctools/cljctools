(ns cljctools.csp.spec
  #?(:cljs (:require-macros [cljctools.csp.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(do (clojure.spec.alpha/check-asserts true))


(s/def ::op keyword?)
(s/def ::op-status (s/nillable #{::complete ::error}))

