(ns cljctools.bytes.spec
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::bytes #?(:clj bytes?
                  :cljs #(instance? js/Buffer %)))