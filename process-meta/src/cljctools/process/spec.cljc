(ns cljctools.process.spec
  #?(:cljs (:require-macros [cljctools.process.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::process some?)
(s/def ::exit-code int?)