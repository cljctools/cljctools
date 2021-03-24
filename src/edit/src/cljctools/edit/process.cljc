(ns cljctools.edit.process
  (:require
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   #?(:cljs [cljs.reader :refer [read-string]])
   #?(:cljs [goog.string.format])
   #?(:cljs [goog.string :refer [format]])
   [clojure.spec.alpha :as s]

   [clojure.walk]

   [rewrite-clj.zip :as z]
   [rewrite-clj.parser :as p]
   [rewrite-clj.node :as n]
   [rewrite-clj.paredit]
   [cljfmt.core]

   [cljctools.edit.spec :as edit.spec]
   [cljctools.edit.protocols :as edit.protocols]
   [cljctools.edit.core :as edit.core]))

(s/def ::id (s/or :keyword keyword? :string string?))

(s/def ::create-opts (s/keys :req [::id]
                             :opt []))

(defonce ^:private registryA (atom {}))

(declare)

(defn create-editing-process
  [{:keys [::id] :as opts}]
  {:pre [(s/assert ::create-opts opts)]
   :post [(s/assert ::edit.spec/editing-process %)]}
  (let [stateA (atom nil)
        op| (chan 10)

        editing-process
        ^{:type ::edit.spec/editing-process}
        (reify
          edit.protocols/EditingProcess
          edit.protocols/Release
          (release*
           [_]
           (close! op|))
          #?(:clj clojure.lang.IDeref)
          #?(:clj (deref [_] @stateA))
          #?(:cljs cljs.core/IDeref)
          #?(:cljs (-deref [_] @stateA)))]

    (reset! stateA (merge
                    opts
                    {:opts opts
                     ::edit.spec/op| op|}))
    (swap! registryA assoc id)
    (go
      (loop []
        (let [[value port] (alts! [op|])]
          (when value
            (condp = port

              op|
              (condp = (:op value)

                ::edit.spec/op-format-current-form
                (let [])
                (do ::ignore-other-ops)))
            (recur)))))
    editing-process))

(defmulti release
  "Releases the instance"
  {:arglists '([id] [instance])} (fn [x & args] (type x)))
(defmethod release :default
  [id]
  (when-let [instance (get @registryA id)]
    (release instance)))
(defmethod release ::edit.spec/editing-process
  [instance]
  {:pre [(s/assert ::edit.spec/editing-process instance)]}
  (edit.protocols/release* instance)
  (swap! registryA dissoc (get @instance ::id)))