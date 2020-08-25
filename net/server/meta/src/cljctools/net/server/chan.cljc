(ns cljctools.net.server.chan
  #?(:cljs (:require-macros [cljctools.net.server.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.net.server.spec :as server.spec]))

(s/def ::start-server (s/keys :req-un [::out|]))
(s/def ::stop-server (s/keys :req-un [::out|]))

(s/def ::ws-connected (s/keys :req []))
(s/def ::ws-closed (s/keys :req [::server.spec/num-code ::server.spec/reason-text]))
(s/def ::ws-error (s/keys :req [] :req-un [::error]))


(defn create-channels
  []
  (let [ops| (chan 10)
        ops|m (mult ops|)
        ws-evt| (chan (sliding-buffer 50))
        ws-evt|m (mult ws-evt|)
        ws-recv| (chan (sliding-buffer 50))
        ws-recv|m (mult ws-recv|)]
    {::ops| ops|
     ::ops|m ops|m
     :ws-evt| ws-evt|
     :ws-evt|m ws-evt|m
     :ws-recv| ws-recv|
     :ws-recv|m ws-recv|m}))

(defn start-server
  ([channels opts]
   (start-server channels opts (chan 1)))
  ([channels opts out|]
   (put! (::ops| channels) {:op ::start-server :out out|})
   out|))

(defn stop-server
  ([channels opts]
   (stop-server channels opts (chan 1)))
  ([channels opts out|]
   (put! (::ops| channels) {:op ::stop-server :out out|})
   out|))

(defn ws-connected
  [to|]
  (put! to| {:op ::ws-connected}))

(defn ws-closed
  [to| num-code reason-text]
  (put! to| {:op ::ws-closed ::server.spec/num-code num-code ::server.spec/reason-text reason-text}))

(defn ws-error
  [to| error]
  (put! to| {:op ::ws-error :error error}))

(defn ws-recv
  [to| value]
  (put! to| value))