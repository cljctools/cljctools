(ns cljctools.net.server.spec
  #?(:cljs (:require-macros [cljctools.net.server.spec]))
  (:require
   [clojure.spec.alpha :as s]))


(s/def ::with-websocket-endpoint? boolean?)
(s/def ::service-map any?)
(s/def ::routes set?)
(s/def ::ws-paths any?)
(s/def ::host any?)
(s/def ::port any?)

(s/def ::num-code int?)
(s/def ::reason-text string?)

(s/def ::error any?)