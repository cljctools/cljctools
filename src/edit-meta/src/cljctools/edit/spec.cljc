(ns cljctools.edit.spec
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::ns-symbol symbol?)