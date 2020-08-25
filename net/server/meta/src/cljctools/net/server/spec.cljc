(ns cljctools.nrepl.spec
  #?(:cljs (:require-macros [cljctools.nrepl.spec]))
  (:require
   [clojure.spec.alpha :as s]))


(s/def ::with-websocket-endpoint? boolean?)
(s/def ::num-code int?)
(s/def ::reason-text string?)