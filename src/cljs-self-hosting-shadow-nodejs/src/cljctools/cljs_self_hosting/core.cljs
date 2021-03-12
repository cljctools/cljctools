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
   [cljs.env :as env]
   [shadow.cljs.bootstrap.node :as boot]
   [cljctools.cljs-self-hosting.spec :as cljs-self-hosting.spec]
   [cljctools.cljs-self-hosting.protocols :as cljs-self-hosting.protocols]))

(defonce fs (js/require "fs"))
(defonce path (js/require "path"))

(s/def ::path string?)
(s/def ::load-on-init some?)

(s/def ::init-opts (s/keys :req-un [::path
                                    ::load-on-init]
                           :opt-un []))

(s/def ::eval-str-opts (s/keys :req [::cljs-self-hosting.spec/code-str
                                     ::cljs-self-hosting.spec/ns-symbol]
                               :opt []))

(defn create-compiler
  []
  {:post [(s/assert ::cljs-self-hosting.spec/compiler %)]}
  (let [stateA (atom nil)
        compile-state-ref (env/default-compiler-env)
        compiler
        ^{:type ::cljs-self-hosting.spec/compiler}
        (reify
          cljs-self-hosting.protocols/Compiler
          (init*
            [_ opts]
            {:pre [(s/assert ::init-opts opts)]}
            (prn ::init)
            (let [result| (chan 1)]
              (boot/init
               compile-state-ref
               opts
               (fn []
                 (prn ::boot-initialized)
                 (let [eval cljs.core/*eval*]
                   (set! cljs.core/*eval*
                         (fn [form]
                           (binding [cljs.env/*compiler* compile-state-ref
                                     *ns* (find-ns cljs.analyzer/*cljs-ns*) #_(find-ns 'mult.extension)
                                     cljs.js/*eval-fn* cljs.js/js-eval]
                             (eval form)))))
                 (close! result|)))
              result|))
          (eval-data*
            [_ opts])
          (eval-str*
            [_ opts]
            {:pre [(s/assert ::eval-str-opts opts)]}
            (let [{:keys [::cljs-self-hosting.spec/code-str
                          ::cljs-self-hosting.spec/ns-symbol]} opts
                  result| (chan 1)]
              (cljs/eval-str
               compile-state-ref
               code-str
               "[test]"
               {:eval cljs/js-eval
                :ns ns-symbol
                :load (partial boot/load compile-state-ref)}
               (fn [result]
                 (put! result| result #(close! result|))))
              result|))
          (compile-js-str*
            [_ opts])

          cljs-self-hosting.protocols/Release
          (release*
            [_]
            (do nil))

          cljs.core/IDeref
          (-deref [_] @stateA))]
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