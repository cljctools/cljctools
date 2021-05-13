(ns cljctools.bittorrent.dht-crawl.socket-spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bittorrent.dht-crawl.socket-protocols :as socket-protocols]))

(s/def ::host string?)
(s/def ::port int?)

(s/def ::on-message ifn?)
(s/def ::on-listening ifn?)
(s/def ::on-error ifn?)

(s/def ::socket #(and
                  (satisfies? socket-protocols/Socket %)
                  #?(:clj (satisfies? clojure.lang.IDeref %))
                  #?(:cljs (satisfies? cljs.core/IDeref %))))