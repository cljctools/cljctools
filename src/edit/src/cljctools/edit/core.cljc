(ns cljctools.edit.core
  (:require
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   #?(:cljs [cljs.reader :refer [read-string]])
   #?(:cljs [goog.string.format])
   #?(:cljs [goog.string :refer [format]])
   [clojure.spec.alpha :as s]
   [edamame.core]
   [cljctools.edit.spec :as edit.spec]))

(defn read-ns-symbol
  "Read the namespace name from a string (beggining of text file).
   At least the (ns ..) form should be par tof string.
   String can be invalid, as long as ns form is valid. 
   Takes the second form which is namespace symbol"
  [string]

  (let [form (edamame.core/parse-string string)
        ns-symbol (second form)]
    ns-symbol))