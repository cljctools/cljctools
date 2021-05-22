(ns cljctools.datagram-socket.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.datagram-socket.protocols :as datagram-socket.protocols]))

(s/def ::host string?)
(s/def ::port int?)

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))

(s/def ::msg| ::channel)
(s/def ::evt| ::channel)
(s/def ::ex| ::channel)

(s/def ::socket #(and
                  (satisfies? datagram-socket.protocols/Socket %)
                  #?(:clj (satisfies? clojure.lang.IDeref %))
                  #?(:cljs (satisfies? cljs.core/IDeref %))))