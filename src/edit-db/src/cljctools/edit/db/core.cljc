(ns cljctools.edit.db.core
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

   [datascript.core :as d]
   [datascript.db :as db])
  #?(:cljs
     (:import [goog.string StringBuffer])))

(defn create-schema
  []
  {:node/type {:db/valueType :db.type/keyword
               :db/cardinality :db.cardinality/one}

   :node/tag {:db/valueType :db.type/keyword
              :db/cardinality :db.cardinality/one}})


(defn parse
  "Returns rewrite-clj nodes and initial data for db"
  [string]
  (let [reader (reader/string-reader string)
        nodes (->> (repeatedly #(parser/parse reader))
                   (sequence
                    (comp
                     (take-while identity)
                     (drop-while (complement
                                  (fn [node]
                                    (= :seq (node/node-type node))))))))
        data  (mapv (fn [node]
                      {:db/id -1}) nodes)]
    [nodes data]))