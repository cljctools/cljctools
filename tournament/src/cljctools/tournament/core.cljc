(ns cljctools.tournament.core
  #?(:cljs (:require-macros [cljctools.tournament.core]))
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as sgen]
   [clojure.spec.test.alpha :as stest]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]

   [cljctools.tournament.spec :as tournament.spec]))