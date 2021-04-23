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
(defonce Webtorrent (js/require "webtorrent"))
(defonce BittorrentDHT (js/require "bittorrent-dht"))
(defonce fetchMetadata (js/require "bep9-metadata-dl"))
(defonce MagnetURI (js/require "magnet-uri"))


(defn start
  [{:keys [:peer-index] :as opts}]
  (go
    (let [#_client #_(Webtorrent.
                      (clj->js
                       {"dhtPort" (+ 6880 peer-index)}))
          #_dht #_(. client -dht)
          dht (BittorrentDHT.
               (clj->js
                {"nodeId" "9859552c412933025559388fe1c438422e3afee7"}))]
      (.listen dht (+ 6880 peer-index)
               (fn []))
      (.on dht "ready"
           (fn []
             (println ::dht-ready (+ 6880 peer-index))
             #_(println (.. dht (toJSON) -nodes))))
      (.on dht "announce"
           (fn [peer info-hash]
             (println ::announce)
             (println (.-host peer) (.-port peer))
             (println (.toString info-hash "hex"))
             (->
              (fetchMetadata
               (.toString info-hash "hex")
               (clj->js
                {"maxConns" 10
                 "fetchTimeout" 30000
                 "socketTimeout" 5000
                 "dht" dht}))
              (.then (fn [metadata]
                       (println (.. metadata -info -name (toString "utf-8")))))
              (.catch (fn [error]
                        (println ::error error))))))
      (.on dht "error"
           (fn [error]
             (println ::dht-error)
             (println error)
             (.destroy dht))))))