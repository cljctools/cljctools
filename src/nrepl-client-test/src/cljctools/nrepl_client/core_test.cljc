(ns cljctools.nrepl-client.core-test
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
   [clojure.set :refer [subset?]]

   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as sgen]
   [clojure.spec.test.alpha :as stest]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test :refer [is run-all-tests testing deftest run-tests]]

   [cljctools.nrepl-client.core :as nrepl-client.core])
  #?(:clj
     (:import
      [java.io ByteArrayOutputStream
       EOFException
       InputStream
       IOException
       OutputStream
       PushbackInputStream])))

#_(deftest arithmetic
    (testing "Arithmetic"
      (testing "with positive integers"
        (is (= 4 (+ 2 2)))
        (is (= 7 (+ 3 4))))
      (testing "with negative integers"
        (is (= -4 (+ -2 -2)))
        (is (= -1 (+ 3 -4))))))


(deftest bencode
  (testing "Bencode works, and works the same on node and jvm:"
    (testing "encoding"
      (is (= (nrepl-client.core/encode->str {:a "bc"}) "d1:a2:bce"))
      (is (= (nrepl-client.core/decode "d1:a2:bce") {:a "bc"})))))