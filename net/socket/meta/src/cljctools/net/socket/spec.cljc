(ns cljctools.net.socket.spec
  #?(:cljs (:require-macros [cljctools.net.socket.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::url string?)
(s/def ::num-code int?)
(s/def ::reason-text string?)