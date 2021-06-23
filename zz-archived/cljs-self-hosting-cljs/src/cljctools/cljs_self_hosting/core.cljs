(ns cljctools.cljs-self-hosting.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   [cljs.reader :refer [read-string]]
   [goog.string.format]
   [goog.string :refer [format]]
   [clojure.spec.alpha :as s]

   [cljs.js :as cljs]
   [cljs.analyzer :as ana]
   [cljs.tools.reader :as r]
   [cljs.tools.reader.reader-types :refer [string-push-back-reader]]
   [cljs.tagged-literals :as tags]

   [cljctools.cljs-self-hosting.spec :as cljs-self-hosting.spec]
   [cljctools.cljs-self-hosting.protocols :as cljs-self-hosting.protocols]))

(s/def ::init-opts (s/keys :req []
                           :opt []))

(defn create-compiler
  []
  {:post [(s/assert ::cljs-self-hosting.spec/compiler %)]}
  (let [stateA (atom nil)
        compile-state-ref (cljs/empty-state)
        compiler
        ^{:type ::cljs-self-hosting.spec/compiler}
        (reify
          cljs-self-hosting.protocols/Compiler
          (init*
            [_ opts]
            {:pre [(s/assert ::init-opts opts)]}
            (prn ::init)
            (go
              (let [eval cljs.core/*eval*]
                (set! cljs.core/*eval*
                      (fn [form]
                        (binding [cljs.env/*compiler* compile-state-ref
                                  *ns* #_(find-ns cljs.analyzer/*cljs-ns*) (find-ns 'deathstar.extension)
                                  cljs.js/*eval-fn* cljs.js/js-eval]
                          (eval form)))))))
          (eval-data*
            [_ opts])
          (eval-str*
            [_ opts])
          (compile-js-str*
            [_ opts]))]
    (reset! stateA {::cljs-self-hosting.spec/compile-state-ref compile-state-ref})
    compiler))

(defn init
  [compiler opts]
  (cljs-self-hosting.protocols/init* compiler opts))

(defn eval-data
  [compiler opts]
  (cljs-self-hosting.protocols/eval-data* compiler opts))

(defn eval-str
  [compiler opts]
  (cljs-self-hosting.protocols/eval-str* compiler opts))

(defn compile-js-str
  [compiler opts]
  (cljs-self-hosting.protocols/compile-js-str* compiler opts))