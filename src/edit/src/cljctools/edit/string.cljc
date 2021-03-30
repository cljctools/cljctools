(ns cljctools.edit.string
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

   [cljctools.edit.spec :as edit.spec])
  #?(:cljs
     (:import [goog.string StringBuffer])))

 
(defn ^{:deprecated "0.0.0"} position-at
  "[deprecated] we never need offset"
  [string offset]
  (let [reader (reader/string-reader string)]
    (loop []
      (let [c (r/read-char reader)
            line (r/get-line-number reader)
            column (r/get-column-number reader)
            current-offset #?(:clj (.. reader -rdr -rdr -rdr -rdr -s-pos)
                              :cljs (.. reader -rdr -rdr -s-pos))]
        (cond
          (= offset current-offset)
          [line column]

          (nil? c)
          (reader/throw-reader reader "Unexpected EOF.")

          :else (recur))))))

(defn ^{:deprecated "0.0.0"} offset-at
  "[deprecated] we never need offset"
  [string [row col :as position]]
  (let [reader (reader/string-reader string)
        string-buffer (StringBuffer.)]
    (loop []
      (let [c (r/read-char reader)
            line (r/get-line-number reader)
            column (r/get-column-number reader)
            current-offset #?(:clj (.. reader -rdr -rdr -rdr -rdr -s-pos) ; does not work on jvm
                              :cljs (.. reader -rdr -rdr -s-pos))]
        (cond
          (= [line column] [row col])
          current-offset

          (nil? c)
          (reader/throw-reader reader "Unexpected EOF.")

          :else (recur))))))

(defn split-at-position
  "note: normalizes new lines"
  [string [row col :as position]]
  (let [lines (clojure.string/split string #"\r?\n" -1)
        string-left (as-> lines x
                      (take (dec row) x)
                      (vec x)
                      (conj x (->
                               (get lines (dec row))
                               (subs 0 (dec col))))
                      (clojure.string/join "\n" x)
                      #_(clojure.string/reverse))
        string-right (as-> lines x
                       (drop row x)
                       (conj x (->
                                (get lines (dec row))
                                (subs (dec col))))
                       (clojure.string/join "\n" x))]
    [string-left string-right])
  #_(let [offset (offset-at string [6755 19] #_[29 31])
          string-left (subs string 0 offset)
          string-right (subs string offset)]
      (println offset)
      (println (subs string-left (- (count string-left) 100))))
  #_(let [reader (reader/string-reader string)
          left-string-buffer (StringBuffer.)
          right-string-buffer (StringBuffer.)]
      (loop []
        (let [c (r/read-char reader)
              line (r/get-line-number reader)
              column (r/get-column-number reader)]
          (cond
            (nil? c)
            (do nil)

            (or
             (< line row)
             (and (= line row) (<= column col)))
            (do
              (.append  left-string-buffer c)
              (recur))

            (or
             (and (= line row) (> column col))
             (> line row))
            (do
              (.append  right-string-buffer c)
              (recur)))))
      [left-string-buffer right-string-buffer]))
