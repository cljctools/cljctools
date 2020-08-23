(ns cljctools.cljc.core
  #?(:cljs (:require-macros [cljctools.cljc.core]))
  (:require
   [clojure.string]
   #?(:cljs [goog.date :as gdate]))
  #?(:clj
     (:import
      java.util.Date)))


(defn str->int [s]
  #?(:clj  (java.lang.Integer/parseInt s)
     :cljs (js/parseInt s)))

(defn rand-uuid []
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(defn make-inst
  "Returns a damn inst"
  []
  #?(:clj (java.util.Date.)
     :cljs (js/Date.)))