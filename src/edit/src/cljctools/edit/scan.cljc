(ns cljctools.edit.scan
  (:require
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   #?(:cljs [cljs.reader :refer [read-string]])
   #?(:cljs [goog.string.format])
   #?(:cljs [goog.string :refer [format]])
   [clojure.spec.alpha :as s]

   [instaparse.core]))


(def scan-parser
  (instaparse.core/parser
   "
    
    
    "))

(defn scan
  "Find boundaries of current form at position
   It only need to parse the shape of the form, without contents
   For example, foo :foo ( ,,, )  { ,,, } [ ,,, ] #?(,,,,) #:foob/bar{,,,} etc. - only find the boundaries of the shape"
  [string position])