(ns cljctools.edit.spec
  (:require
   [clojure.spec.alpha :as s]
   [clojure.core.async]
   [cljctools.edit.protocols :as edit.protocols]))

(s/def ::ns-symbol symbol?)

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))
(s/def ::mult #?(:clj #(satisfies? clojure.core.async.Mult %)
                 :cljs #(satisfies? cljs.core.async/Mult %)))

(s/def ::editing-process #(and
                           (satisfies? edit.protocols/EditingProcess %)
                           (satisfies? edit.protocols/Release %)
                           #?(:clj (satisfies? clojure.lang.IDeref %))
                           #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::op| ::channel)
(s/def ::op #{::op-format-current-form})
