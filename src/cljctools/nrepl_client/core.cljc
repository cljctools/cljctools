(ns cljctools.nrepl-client.core
  (:refer-clojure :exclude [eval])
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

(s/def ::op #{"eval" "clone"})
(s/def ::code string?)
(s/def ::session string?)

(s/def ::nrepl-op-data (s/or
                        ::op-eval
                        (s/keys :req-un [::op
                                         ::code
                                         ::session])
                        ::op-clone-session
                        (s/keys :req-un [::op])))

(s/def ::eval-opts (s/keys :req [
                                 ::code
                                 ::session]
                           :opt []))

(s/def ::clone-session-opts (s/keys :req []
                                    :opt []))

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))

(s/def ::send| ::channel)
(s/def ::recv| ::channel)

(s/def ::nrepl-op-opts (s/keys :req [::recv|
                                     ::send|
                                     ::nrepl-op-data]
                               :opt [::done-keys
                                     ::result-keys
                                     ::time-out]))

(declare  eval
          clone-session)

(defn nrepl-op
  [{:as opts
    :keys [::recv|
           ::send|
           ::done-keys
           ::result-keys
           ::nrepl-op-data
           ::time-out]
    :or {done-keys [:status :err]
         result-keys [:value :err]
         time-out 10000}}]
  {:pre [(s/assert ::nrepl-op-opts opts)]}
  (go
    (let [op-id (str (random-uuid))

          xf-message-id-of-this-operation?
          (comp
           (map (fn [value] (decode value)))
           (filter (fn [value] (get value :id))))

          recv|piped (pipe recv| (chan 10 xf-message-id-of-this-operation?) true)

          request (merge
                   nrepl-op-data
                   {:id op-id})
          result| (chan 50)
          error| (chan 1)

          release #(do
                     (close! recv|piped)
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
          recv|piped ([value] (when value
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

(defn clone-session
  [{:as opts
    :keys []}]
  {:pre [(s/assert ::clone-session-opts opts)]}
  (nrepl-op
   {::nrepl-op-data {:op "clone"}
    ::result-keys [:new-session]}))

(defn eval
  [{:as opts
    :keys [::code
           ::session]}]
  {:pre [(s/assert ::eval-opts opts)]}
  (nrepl-op
   {::nrepl-op-data {:op "eval"
                     :code code
                     :session session}}))