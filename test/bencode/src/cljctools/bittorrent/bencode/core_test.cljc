(ns cljctools.bittorrent.bencode.core-test
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.spec.alpha :as s]
   [clojure.set :refer [subset?]]
   #?@(:cljs [[goog.string.format]
              [goog.string :refer [format]]
              [goog.object]
              [cljs.reader :refer [read-string]]])

   [clojure.test :as t :refer [is run-all-tests testing deftest run-tests]]

   [cljctools.bittorrent.bencode.core :as bencode.core]
   [cljctools.codec.core :as codec.core]))

(deftest ^{:foo true} tests
  (testing "encode bytes -> decode -> hex string matches"
    (let [id "197957dab1d2900c5f6d9178656d525e22e63300"
          data {:t (codec.core/hex-decode "aabbccdd")
                :a {"id" (codec.core/hex-decode id)}}
          result (->
                  (bencode.core/encode data)
                  #_(bytes.core/to-string)
                  (bencode.core/decode)
                  (-> (get-in ["a" "id"]))
                  (codec.core/hex-encode-string))]
      (println result)
      (is (= result id)))))