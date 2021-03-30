(ns cljctools.edit.scan
  (:require
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   #?(:cljs [cljs.reader :refer [read-string]])
   #?(:cljs [goog.string.format])
   #?(:cljs [goog.string :refer [format]])
   [clojure.spec.alpha :as s]

   [rewrite-clj.zip :as z]
   [rewrite-clj.parser :as parser]
   [rewrite-clj.parser.core :as parser.core]
   [rewrite-clj.node :as n]
   [rewrite-clj.node.forms :as nforms]
   [rewrite-clj.zip.base :as zip.base]
   [rewrite-clj.reader :as reader]
   [rewrite-clj.paredit]
   [rewrite-clj.node.protocols :as node]

   [clojure.tools.reader.reader-types :as r]

   [cljctools.edit.spec :as edit.spec]
   [cljctools.edit.string :as edit.string])
  #?(:cljs
     (:import [goog.string StringBuffer])))


#_(fn [c]
    (cond (nil? c)               :eof
          (reader/whitespace? c) :whitespace
          (= c *delimiter*)      :delimiter
          :else (get {\^ :meta      \# :sharp
                      \( :list      \[ :vector    \{ :map
                      \} :unmatched \] :unmatched \) :unmatched
                      \~ :unquote   \' :quote     \` :syntax-quote
                      \; :comment   \@ :deref     \" :string
                      \: :keyword}
                     c :token)))

(defn scan
  "A process that scans string in both direction of position.
   Scan understands from where and to where the expresion(s) is, so that substring can be then parsed with rewrite-clj.
   Returns start and end position of a string to pass to rewrite-clj parse-string-all"
  [string position]
  (let [[row col] position
        [lines string-left string-right] (edit.string/split-at-position string position)
        string-left-reversed (clojure.string/reverse string-left)
        reader-left (reader/string-reader string-left-reversed)
        reader-right (reader/string-reader string-right)
        stateV (volatile! {:left-target-position nil
                           :right-target-position nil})]
    (loop []
      (let [char-left (reader/peek reader-left)
            char-right (reader/peek reader-right)]
        (cond
          (= char-left \})
          (do
            #_(r/read-char reader-left)
            (reader/read-until reader-left
                               (fn [c]
                                 (= c \{)))
            (let [reader-left-line (r/get-line-number reader-left)
                  reader-left-column (r/get-column-number reader-left)
                  line (- row reader-left-line)
                  column (- (count (get lines line)) reader-left-column)]
              {:start [line column]
               :end [row (dec col)]})))))))