(ns cljctools.edit.core
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


(defn read-ns-symbol
  "Reads the namespace name (ns foo.bar ,,,) from a string.
   String after ns form can be invalid. 
   File may start with comments, drop-reads one form at a time until finds ns"
  [string]
  (let [reader (reader/string-reader string)
        nodes (->> (repeatedly #(parser/parse reader))
                   (sequence
                    (comp
                     (drop-while (complement
                                  (fn [node]
                                    (= :seq (node/node-type node)))))))
                   (take 1))
        node (with-meta
               (nforms/forms-node nodes)
               (meta (first nodes)))
        zloc (zip.base/edn node {:track-position? true})  #_(z/of-string (n/string node))
        zloc-ns (-> zloc
                    z/down
                    (z/find-next
                     (fn [zloc-current]
                       (let [node-type (node/node-type (-> zloc-current z/node))]
                         (or
                          (= node-type :symbol)
                          (= node-type :meta))))))
        ns-symbol (->
                   (if (= :meta (node/node-type (-> zloc-ns z/node)))
                     (->
                      (sequence
                       (comp
                        (take-while identity)
                        (take-while (complement z/end?)))
                       (iterate  z/next zloc-ns))
                      last)
                     zloc-ns)
                   z/sexpr)]
    ns-symbol))


(defn ^{:deprecated "0.0.0"} position-at
  "[deprecated] we never need offset
   get string and position -> normalize -> do stuff -> tell editor back new line (s) positions, it's never offset"
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
  "[deprecated] we never need offset
   get string and position -> normalize -> do stuff -> tell editor back new line (s) positions, it's never offset"
  [string [row col :as position]]
  (let [reader (reader/string-reader string)
        ; we cannot write chars to string buffer, beacuse newline normalization ate our characters
        ; solution - inefficiently do substring as the second step
        ; but this should be one step if we need to max performace
        ; to do that - rewrite tools.reader.reader-types as static functions acting on atom-like state (for line normaliztion etc.)
        ; then we can loop through string and choose what to do without the chain of 4 reader instances - isntead one state + functions
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


(defn parse-forms-at-position
  "Returns a lazy sequence of forms at position. Every next element returns next form expansion.
   On every read from sequence, the string is read just enough to return the next form until the top level form.
   Given e.g. form and position ({:a [:b 1 | ]}), lazy seq will give elements 1 , [:b 1] , {:a [:b 1]} , ({:a [:b 1 |]})
   "
  [string [row col :as position] {:keys [] :or {} :as opts}]
  (let [row 29
        col 31
        lines (clojure.string/split string #"\r?\n" -1)
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
                                (get lines row)
                                (subs (dec col))))
                       (clojure.string/join "\n" x))]
    (println string-left)))

#_(let [position [29 31]
        offset (offset-at string position)
        string-left (-> (subs string 0 offset)
                        (clojure.string/reverse))
        string-right (subs string offset)]
    (println offset)
    (println string-left))