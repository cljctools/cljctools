(ns cljctools.process.spec
  #?(:cljs (:require-macros [cljctools.process.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::process some?)
(s/def ::code int?)
(s/def ::signal some?)
(s/def ::color string?)
(s/def ::process-name string?)

