(ns find.app.bittorrent
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.string]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]))

(defonce fs (js/require "fs-extra"))
(defonce path (js/require "path"))
(defonce BittorrentDHT (js/require "bittorrent-dht"))
(defonce MagnetURI (js/require "magnet-uri"))


(defn start
  [{:keys [:peer-index] :as opts}]
  (go
    (let [dht (BittorrentDHT.)]
      (.listen dht (+ 6880 peer-index)
               (fn []
                 (println ::dht-listening (.address dht))))
      (.on dht "ready"
           (fn []
             (println ::dht-ready)
             #_(println (.. dht (toJSON) -nodes))))
      (.on dht "announce"
           (fn [peer info-hash]
             (println ::announce)
             (println info-hash)
             (println (.-host peer) (.-port peer))
             (println (.toString info-hash "hex"))))
      (.on dht "error"
           (fn [error]
             (println ::dht-error)
             (println error)
             (.destroy dht))))))