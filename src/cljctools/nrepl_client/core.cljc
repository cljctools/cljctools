(ns cljctools.nrepl-client.core
  (:refer-clojure :exclude [eval clone])
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
   [clojure.spec.alpha :as s]))

#?(:cljs 
   (do
     (when (exists? js/module)
       (def path (js/require "path"))
       #_(set! js/WebSocket ws)
       #_(set! js/module.exports exports))))

#?(:cljs
   (do
     (when (exists? js/module)

       (def bencode (js/require "bencode"))

       (defn encode
         "Returns bencode string"
         [data]
         (.encode bencode (clj->js data)))

       (defn decode
         "Returns edn with :keywordize-keys true"
         [bencode-str]
         #(as-> bencode-str v
            (.toString v)
            (.decode bencode v "utf8")
            (js->clj v :keywordize-keys true)))
       ;;
       )))

(s/def ::nrepl-op-request-data (s/map-of keyword? some?))
(s/def ::nrepl-op-responses (s/coll-of some?))
(s/def ::result-keys (s/coll-of keyword?))
(s/def ::done-keys (s/coll-of keyword?))
(s/def ::error any?)

(s/def ::code string?)
(s/def ::session string?)

(declare  eval
          clone)

(defn nrepl-op
  [{:as opts
    :keys [::recv|mult
           ::send|
           ::done-keys
           ::result-keys
           ::data
           ::time-out]
    :or {done-keys [:status :err]
         result-keys [:value :err]
         time-out 10000}}]
  (go
    (let [op-id (str (random-uuid))

          xf-message-id-of-this-operation?
          (comp
           (map (fn [value] (decode value)))
           (filter (fn [value] (get value :id))))

          recv|tap (tap recv|mult (chan 10 xf-message-id-of-this-operation?))

          request (merge
                   data
                   {:id op-id})
          result| (chan 50)
          error| (chan 1)

          release #(do
                     (untap recv|mult recv|tap)
                     (close! recv|tap)
                     (close! error|))]
      (try
        (prn ::sending)
        (prn request)
        (put! send| (encode request))
        (catch js/Error error (put! error| {:error (ex-info
                                                    "Error xfroming/sending nrepl op"
                                                    request
                                                    error)})))
      (loop [timeout| (timeout time-out)]
        (alt!
          recv|tap ([value] (when value
                              (>! result| value)
                              (if (not-empty (select-keys value done-keys))
                                (do
                                  (release)
                                  (close! result|)
                                  (let [responses (<! (a/into [] result|))]
                                    (transduce
                                     (comp
                                      (keep #(or (not-empty (select-keys % result-keys)) nil))
                                      #_(mapcat vals))
                                     merge
                                     {::request request
                                      ::responses responses}
                                     responses)))
                                (recur (timeout time-out)))))
          timeout| ([value] (do
                              (release)
                              {:error (ex-info
                                       "Error: nrepl op timed out"
                                       request)}))
          error| ([value] (do
                            (release)
                            value)))))))

(defn clone
  [{:as opts
    :keys [:code
           :session]}]
  (nrepl-op
   {::data (merge {:op "clone"})
    ::result-keys [:new-session]}))

(defn eval
  [{:as opts
    :keys [:code
           :session]}]
  (nrepl-op
   {::data (merge {:op "eval"}
                  opts)}))