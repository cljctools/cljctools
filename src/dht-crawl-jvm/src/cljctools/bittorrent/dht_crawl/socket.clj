(ns cljctools.bittorrent.dht-crawl.socket
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.string])
  (:import
   (java.net DatagramSocket InetSocketAddress)))


(defn create
  [{:as opts
    :keys []}]
  (let [socket (DatagramSocket. nil)]
    
    socket))

