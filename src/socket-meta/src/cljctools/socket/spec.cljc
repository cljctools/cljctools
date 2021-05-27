(ns cljctools.socket.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.socket.protocols :as socket.protocols]))

(s/def ::host string?)
(s/def ::port int?)

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))

(s/def ::msg| ::channel)
(s/def ::evt| ::channel)
(s/def ::ex| ::channel)

(s/def ::time-out int?)

(s/def ::socket #(and
                  (satisfies? socket.protocols/Socket %)
                  #?(:clj (instance? clojure.lang.IDeref %))
                  #?(:cljs (satisfies? cljs.core/IDeref %))))