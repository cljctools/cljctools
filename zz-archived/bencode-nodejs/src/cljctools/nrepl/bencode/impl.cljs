(ns cljctools.nrepl.bencode.impl
  (:require
   [clojure.core.async :as a :refer [<! >!  chan go alt! take! put! offer! poll! alts! pub sub unsub
                                     timeout close! to-chan  mult tap untap mix admix unmix
                                     pipeline pipeline-async go-loop sliding-buffer dropping-buffer]]
   [goog.string :refer [format]]
   [clojure.string :as string]
   [clojure.pprint :refer [pprint]]
   [cljs.reader :refer [read-string]]
   [cljs.nodejs :as node]))

(def bencode (node/require "bencode"))

(defn encode
  "Returns bencode string"
  [edn-value]
  (.encode bencode (clj->js edn-value)))

(defn decode
  "Returns edn with :keywordize-keys true"
  [bencode-str]
  #(as-> decode<-str v
     (.toString v)
     (.decode bencode v "utf8")
     (js->clj v :keywordize-keys true)))


