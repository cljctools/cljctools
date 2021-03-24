(ns cljctools.edit.process.spec
  (:require
   [clojure.spec.alpha :as s]
   [clojure.core.async]
   [cljctools.edit.process.protocols :as edit.process.protocols]))


(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))
(s/def ::mult #?(:clj #(satisfies? clojure.core.async.Mult %)
                 :cljs #(satisfies? cljs.core.async/Mult %)))

(s/def ::edit-process #(and
                        (satisfies? edit.process.protocols/EditProcess %)
                        (satisfies? edit.process.protocols/Release %)
                        #?(:clj (satisfies? clojure.lang.IDeref %))
                        #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::op| ::channel)
(s/def ::op #{::op-format-current-form})
