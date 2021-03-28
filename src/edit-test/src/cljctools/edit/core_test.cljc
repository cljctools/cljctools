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

(def clojure-core-stringA (atom nil))
(def tmp-dir-path "./tmp")

#?(:cljs (do
           (def fs (js/require "fs"))
           (def path (js/require "path"))
           (def cp (js/require "child_process"))

           (use-fixtures :once
             {:before (fn []
                        (async done
                               (go
                                 (when-not (.existsSync fs (.join path (.cwd js/process) tmp-dir-path "clojure"))
                                   (.execSync cp
                                          (str "mkdir -p " tmp-dir-path " && "
                                               "cd " tmp-dir-path " && "
                                               "git clone https://github.com/clojure/clojure"
                                               " && " "cd ../")
                                          (clj->js {:cwd (.cwd js/process)})))
                                 (let [clojure-core-string (->
                                                            (.readFileSync
                                                             fs
                                                             (.join path (.cwd js/process) tmp-dir-path "clojure/src/clj/clojure/core.clj")
                                                             (clj->js {:encoding "utf8"}))
                                                            (.toString))]
                                   (reset! clojure-core-stringA  clojure-core-string))
                                 (done))))
              :after (fn []
                       (async done
                              (go
                                #_(.execSync cp (format "rm -rf %s" (.join path (.cwd js/process) tmp-dir-path))
                                             (clj->js {:cwd (.cwd js/process)}))
                                (done))))})))

#?(:clj (do
          (use-fixtures :once
            (fn [f]
              (let [pwd (System/getProperty "user.dir")]
                (when-not (.exists (io/file (str pwd "/tmp/clojure")))
                  (sh
                   "sh" "-c"
                   (str
                    "mkdir -p " tmp-dir-path
                    " && cd " tmp-dir-path
                    " && "
                    "git clone https://github.com/clojure/clojure")))

                (let [clojure-core-string (slurp (io/file (str pwd "/tmp/clojure/src/clj/clojure/core.clj")))]
                  (reset! clojure-core-stringA  clojure-core-string))
                (f)
                #_(sh
                   "sh" "-c"
                   (str "rm -rf " tmp-dir-path)))))))

(deftest ^{:foo true} read-ns-symbol
  (testing "edit.core/read-ns-symbol"
    (let [clojure-core-string @clojure-core-stringA
          ns-symbol (edit.core/read-ns-symbol clojure-core-string)]
      (println ns-symbol)
      (is (= ns-symbol
             'clojure.core)))))