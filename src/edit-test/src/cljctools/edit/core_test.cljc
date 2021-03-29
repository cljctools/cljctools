(ns cljctools.edit.core-test
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

   [clojure.set :refer [subset?]]

   [clojure.spec.gen.alpha :as sgen]
   [clojure.spec.test.alpha :as stest]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test :refer [is run-all-tests testing deftest use-fixtures run-tests]]
   #?(:cljs [clojure.test :refer [async]])

   #?(:clj [clojure.java.shell :refer [sh]])
   #?(:clj [clojure.java.io :as io])
   [cljctools.edit.core :as edit.core]))

(def tmp-dir "tmp")
(def pwd #?(:clj (System/getProperty "user.dir")
            :cljs (.cwd js/process)))

#?(:cljs (do
           (def fs (js/require "fs"))
           (def path (js/require "path"))
           (def cp (js/require "child_process"))

           (use-fixtures :once
             {:before (fn []
                        (async done
                               (go
                                 (when-not (.existsSync fs (.join path pwd tmp-dir "/clojure"))
                                   (.execSync cp
                                              (str "mkdir -p " tmp-dir " && "
                                                   "cd " tmp-dir " && "
                                                   "git clone https://github.com/clojure/clojure"
                                                   " && " "cd ../")
                                              (clj->js {:cwd pwd})))
                                 (done))))
              :after (fn []
                       (async done
                              (go
                                #_(.execSync cp (format "rm -rf %s" (.join path pwd tmp-dir))
                                             (clj->js {:cwd pwd}))
                                (done))))})))

#?(:clj (do
          (use-fixtures :once
            (fn [f]
              (when-not (.exists (io/file (str pwd "/" tmp-dir "/clojure")))
                (sh
                 "sh" "-c"
                 (str
                  "mkdir -p " tmp-dir
                  " && cd " tmp-dir
                  " && "
                  "git clone https://github.com/clojure/clojure")))
              (f)
              #_(sh
                 "sh" "-c"
                 (str "rm -rf " tmp-dir))))))

(defn read-clojure-core-string
  []
  (let [clojure-core-path (str pwd "/" tmp-dir "/clojure/src/clj/clojure/core.clj")
        clojure-core-string
        #?(:clj (slurp (io/file clojure-core-path))
           :cljs (->
                  (.readFileSync
                   fs
                   clojure-core-path
                   (clj->js {:encoding "utf8"}))
                  (.toString)))]
    clojure-core-string))


(deftest ^{:foo true} read-ns-symbol
  (testing "edit.core/read-ns-symbol"
    (let [clojure-core-string (read-clojure-core-string)
          ns-symbol (time (edit.core/read-ns-symbol clojure-core-string))]
      (is (= ns-symbol
             'clojure.core)))))


(deftest ^{:foo true} parse-forms-at-position
  (testing "edit.core/parse-forms-at-position"
    (let [clojure-core-string (read-clojure-core-string)
          form (time (edit.core/parse-forms-at-position clojure-core-string [] {}))]
      (is (= form
             :foo)))))