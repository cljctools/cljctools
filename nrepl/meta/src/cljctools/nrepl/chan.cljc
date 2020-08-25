(ns cljctools.nrepl.chan
  #?(:cljs (:require-macros [cljctools.nrepl.chan]))
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.nrepl.spec :as nrepl.spec]))

(do (clojure.spec.alpha/check-asserts true))

(s/def ::start-server (s/keys :req-un [::out|]))
(s/def ::stop-server (s/keys :req-un [::out|]))


(defn create-channels
  []
  (let [ops| (chan 10)
        ops|m (mult ops|)
        nrepl-enter| (chan (sliding-buffer 10))
        nrepl-enter|m (mult nrepl-enter|)
        nrepl-leave| (chan (sliding-buffer 10))
        nrepl-leave|m (mult nrepl-leave|)]
    {::ops| ops|
     ::ops|m ops|m
     ::nrepl-enter| nrepl-enter|
     ::nrepl-enter|m nrepl-enter|m
     ::nrepl-leave| nrepl-leave|
     ::nrepl-leave|m nrepl-leave|m}))

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