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
   [clojure.spec.alpha :as s]

   #?(:clj [bencode.core]))
  #?(:clj
     (:import
      [java.io ByteArrayOutputStream
       EOFException
       InputStream
       IOException
       OutputStream
       PushbackInputStream])))

#?(:clj
   (do
     (defn encode
       [data]
       (doto (ByteArrayOutputStream.)
         (bencode.core/write-bencode data)))

     (defn encode->str
       [data]
       (.toString (encode data)))

     (defn decode
       [x])))

#?(:cljs
   (do
     (when (exists? js/module)
       (def path (js/require "path"))
       (def bencode (js/require "bencode"))
       #_(set! js/WebSocket ws)
       #_(set! js/module.exports exports)

       (defn encode
         "Returns buffer"
         [data]
         (.encode bencode (clj->js data)))

       (defn encode->str
         [data]
         (.toString (encode data)))

       (defn decode
         "Returns edn with :keywordize-keys true. 
          Warning: try-catches decode error and returns nil"
         [bencode-str]
         (try
           (as-> bencode-str v
             (.toString v)
             (.decode bencode v "utf8")
             (js->clj v :keywordize-keys true))
           (catch js/Error err (do nil))))

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

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))
(s/def ::mult #?(:clj #(satisfies? clojure.core.async.Mult %)
                 :cljs #(satisfies? cljs.core.async/Mult %)))

(s/def ::send| ::channel)
(s/def ::recv|mult ::mult)


(s/def ::opts (s/keys :req [::recv|mult
                            ::send|]
                      :opt [::done-keys
                            ::result-keys
                            ::time-out]))

(s/def ::nrepl-op-opts (s/and
                        ::opts
                        (s/keys :req [::nrepl-op-data])))

(s/def ::eval-opts (s/and
                    ::opts
                    (s/keys :req [::code
                                  ::session]
                            :opt [])))

(s/def ::clone-session-opts (s/and
                             ::opts
                             (s/keys :req []
                                     :opt [])))

(declare  eval
          clone-session)

(defn nrepl-op
  [{:as opts
    :keys [::recv|mult
           ::send|
           ::done-keys
           ::result-keys
           ::nrepl-op-data
           ::time-out]
    :or {done-keys [:status :value :err]
         result-keys [:value :err]
         time-out 100}}]
  #_{:pre [(or (println opts) (s/assert ::nrepl-op-opts opts))]}
  (go
    (let [op-id (str #?(:clj (java.util.UUID/randomUUID)
                        :cljs (random-uuid)))

          xf-message-id-of-this-operation?
          (comp
           (map (fn [value]
                  (println value)
                  (let [data (decode value)]
                    (println data)
                    data)))
           (filter (fn [value]
                     (get value :id))))

          recv|tap (tap recv|mult (chan 100 xf-message-id-of-this-operation?))

          request (merge
                   nrepl-op-data
                   {:id op-id})
          result| (chan 50)
          error| (chan 1)



          release #(do
                     (untap recv|mult recv|tap)
                     (close! recv|tap)
                     (close! error|))]
      (try
        (prn ::sending request)
        (put! send| (encode request))
        (catch
         #?(:cljs js/Error)
         #?(:clj Exception)
          error (put! error| {:error (ex-info
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
                                    (let [result (transduce
                                                  (comp
                                                   (keep #(or (not-empty (select-keys % result-keys)) nil))
                                                   #_(mapcat vals))
                                                  merge
                                                  {::request request
                                                   ::responses responses}
                                                  responses)]
                                      #_(println ::result)
                                      #_(println (::request result))
                                      #_(println (::responses result))
                                      result)))
                                (recur (timeout time-out)))))
          timeout| ([value] (do
                              (release)
                              (println ::timed-out request)
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
   (merge
    {::nrepl-op-data {:op "clone"}
     ::result-keys [:new-session]}
    opts)))

(defn eval
  [{:as opts
    :keys [::code
           ::session]}]
  {:pre [(s/assert ::eval-opts opts)]}
  (nrepl-op
   (merge
    {::nrepl-op-data {:op "eval"
                      :code code
                      :session session}}
    opts)))