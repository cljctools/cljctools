(ns cljctools.edit.process.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
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
   [cljctools.edit.core :as edit.core]

   [cljctools.edit.process.spec :as edit.process.spec]
   [cljctools.edit.process.protocols :as edit.process.protocols]))

(declare)

(s/def ::create-opts (s/keys :req []
                             :opt []))

(defn create
  [{:keys [] :as opts}]
  {:pre [(s/assert ::create-opts opts)]
   :post [(s/assert ::edit.process.spec/edit-process %)]}
  (let [stateA (atom nil)
        op| (chan 10)

        edit-process
        ^{:type ::edit.process.spec/edit-process}
        (reify
          edit.process.protocols/EditProcess
          edit.process.protocols/Release
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
                     ::edit.process.spec/op| op|}))
    (swap! registryA assoc id)
    (go
      (loop []
        (let [[value port] (alts! [op|])]
          (when value
            (condp = port

              op|
              (condp = (:op value)

                ::edit.process.spec/op-format-current-form
                (let []
                  (println ::op-format-current-form))
                (do ::ignore-other-ops)))
            (recur)))))
    edit-process))