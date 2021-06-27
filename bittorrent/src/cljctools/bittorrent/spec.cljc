(ns cljctools.bittorrent.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.spec :as bytes.spec]
   [cljctools.bittorrent.protocols :as bittorrent.protocols]))

(s/def ::infohash string?)
(s/def ::infohashBA ::bytes.spec/byte-array)

(s/def ::peer-id string?)
(s/def ::peer-idBA ::bytes.spec/byte-array)

(s/def ::wire #(and
                (satisfies? bittorrent.protocols/Wire %)
                #?(:clj (instance? clojure.lang.IDeref %))
                #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))

(s/def ::recv| ::channel)
(s/def ::send| ::channel)
(s/def ::ex| ::channel)
(s/def ::metadata| ::channel)

(s/def ::create-wire-opts
  (s/keys :req [::send|
                ::recv|
                ::metadata|
                ::infohashBA
                ::peer-idBA]
          :opt [::ex|]))