(ns cljctools.bittorrent.dht-crawl.socket
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]

   [clojure.pprint :refer [pprint]]
   [clojure.string]
   #?@(:cljs
       [[goog.string.format :as format]
        [goog.string :refer [format]]
        [goog.object]
        [cljs.reader :refer [read-string]]])
   [cljctools.bittorrent.dht-crawl.socket-impl :as socket-impl]
   [cljctools.bittorrent.dht-crawl.socket-protocols :as socket-protocols]
   [cljctools.bittorrent.dht-crawl.socket-spec :as socket-spec]))

(defn create
  [opts]
  (socket-impl/create opts))

(defn listen
  [socket]
  {:pre [(s/assert ::socket-spec/socket socket)]}
  (socket-protocols/listen* socket))