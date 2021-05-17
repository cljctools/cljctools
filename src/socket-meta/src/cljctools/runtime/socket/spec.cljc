(ns cljctools.runtime.socket.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.runtime.socket.protocols :as socket.protocols]))

(s/def ::host string?)
(s/def ::port int?)

(s/def ::on-message ifn?)
(s/def ::on-connected ifn?)
(s/def ::on-error ifn?)
(s/def ::time-out int?)

(s/def ::socket #(and
                  (satisfies? socket.protocols/Socket %)
                  #?(:clj (satisfies? clojure.lang.IDeref %))
                  #?(:cljs (satisfies? cljs.core/IDeref %))))