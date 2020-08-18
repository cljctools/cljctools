(ns cljctools.self-hosted.compiler
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]

   [goog.string :refer [format]]
   [clojure.string :as string]
   [clojure.pprint :refer [pprint]]
   [cljs.reader :refer [read-string]]
   [goog.dom :as gdom]
   [goog.events :as events]
   [goog.object :as gobj]
   [cljs.js :as cljs]
   [cljs.analyzer :as ana]
   [cljs.tools.reader :as r]
   [cljs.tools.reader.reader-types :refer [string-push-back-reader]]
   [cljs.tagged-literals :as tags]

   [cljctools.self-hosted.protocols :as p]))

(defn create-compiler
  []
  (let [compile-state-ref (cljs/empty-state)]
    (reify
      p/Compiler
      (-init [_ opts] (let [c| (chan 1)]
                        (do
                          (prn "; cljs initialized")
                          (let [eval cljs.core/*eval*]
                            (set! cljs.core/*eval*
                                  (fn [form]
                                    (binding [cljs.env/*compiler* compile-state-ref
                                              *ns* #_(find-ns cljs.analyzer/*cljs-ns*) (find-ns 'deathstar.extension)
                                              cljs.js/*eval-fn* cljs.js/js-eval]
                                      (eval form)))))
                          (close! c|))
                        c|))
      (-eval-data [_ opts])
      (-compile-str [_ opts])
      (-compile-js-str [_ opts]))))

(defn init
  [compiler opts]
  (p/-init compiler opts))
