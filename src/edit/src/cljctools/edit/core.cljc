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

   [cljctools.edit.spec :as edit.spec]
   [cljctools.edit.string :as edit.string])
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