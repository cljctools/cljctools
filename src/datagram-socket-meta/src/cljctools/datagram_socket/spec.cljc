(ns cljctools.datagram-socket.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.datagram-socket.protocols :as datagram-socket.protocols]))

(s/def ::host string?)
(s/def ::port int?)

(s/def ::on-message ifn?)
(s/def ::on-listening ifn?)
(s/def ::on-error ifn?)

(s/def ::socket #(and
                  (satisfies? datagram-socket.protocols/Socket %)
                  #?(:clj (satisfies? clojure.lang.IDeref %))
                  #?(:cljs (satisfies? cljs.core/IDeref %))))