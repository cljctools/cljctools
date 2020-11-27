(ns cljctools.process.spec
  #?(:cljs (:require-macros [cljctools.process.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::process some?)
(s/def ::code int?)
(s/def ::signal some?)
(s/def ::process-key keyword?)
(s/def ::print-to-stdout? boolean?)
(s/def ::n number?)

(s/def ::cmd string?)
(s/def ::args some?)
(s/def ::child-process-options some?)
    



