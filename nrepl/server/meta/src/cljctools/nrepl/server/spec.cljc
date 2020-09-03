(ns cljctools.nrepl.spec
  #?(:cljs (:require-macros [cljctools.nrepl.spec]))
  (:require
   [clojure.spec.alpha :as s]))


(s/def ::middleware (s/coll-of some?))
(s/def ::host string?)
(s/def ::port int?)