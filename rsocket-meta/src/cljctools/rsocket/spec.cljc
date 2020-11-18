(ns cljctools.rsocket.spec
  #?(:cljs (:require-macros [cljctools.rsocket.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::url string?)
