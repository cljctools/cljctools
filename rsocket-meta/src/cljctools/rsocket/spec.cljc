(ns cljctools.rsocket.spec
  #?(:cljs (:require-macros [cljctools.rsocket.spec]))
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::host string?)
(s/def ::port int?)

(s/def ::connection-side #{::accepting ::initiating})

(s/def ::transport #{::tcp ::websocket})