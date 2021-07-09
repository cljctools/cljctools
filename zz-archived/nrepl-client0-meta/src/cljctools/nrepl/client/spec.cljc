(ns cljctools.nrepl.client.spec
  #?(:cljs (:require-macros [cljctools.nrepl.client.spec]))
  (:require
   [clojure.spec.alpha :as s]))


(s/def ::nrepl-op-request-data (s/map-of keyword? some?))
(s/def ::nrepl-op-responses (s/coll-of some?))
(s/def ::result-keys (s/coll-of keyword?))
(s/def ::done-keys (s/coll-of keyword?))
(s/def ::error any?)

(s/def ::code string?)
(s/def ::session string?)
