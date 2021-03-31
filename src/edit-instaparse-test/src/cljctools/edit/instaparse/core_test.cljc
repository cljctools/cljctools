(ns cljctools.edit.instaparse.core-test
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
   [cljctools.edit.instaparse.core :as edit.instaparse.core]
   [cljctools.edit.test-data.core :as edit.test-data.core]))

(def pwd #?(:clj (System/getProperty "user.dir")
            :cljs (.cwd js/process)))
(def tmp-dir "tmp")

#?(:cljs (do
           (use-fixtures :once
             {:before (fn []
                        (async done
                               (go
                                 (edit.test-data.core/clojure-repo-git-clone pwd tmp-dir)
                                 (done))))
              :after (fn []
                       (async done
                              (go
                                #_(edit.test-data.core/clojure-repo-remove pwd tmp-dir)
                                (done))))})))
#?(:clj (do
          (use-fixtures :once
            (fn [f]
              (edit.test-data.core/clojure-repo-git-clone pwd tmp-dir)
              (f)
              #_(edit.test-data.core/clojure-repo-remove pwd tmp-dir)))))

(deftest ^{:foo true} parse-clojure
  (testing "edit.instaparse.core/parse-clojure"
    (let [clojure-core-string (edit.test-data.core/read-file pwd "tmp/clojure/src/clj/clojure/core.clj")]
      (println (count clojure-core-string))
      (is (= :foo
             :foo)))))
