(ns cljctools.cljs-self-hosting.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.cljs-self-hosting.protocols :as cljs-self-hosting.protocols]))

(s/def ::code-str string?)
(s/def ::ns-symbol symbol?)

(s/def ::compile-state-ref some?)

(s/def ::compiler #(and
                    (satisfies? cljs-self-hosting.protocols/Compiler %)
                    (satisfies? cljs-self-hosting.protocols/Release %)
                    #?(:clj (instance? clojure.lang.IDeref %))
                    #?(:cljs (satisfies? cljs.core/IDeref %))))
