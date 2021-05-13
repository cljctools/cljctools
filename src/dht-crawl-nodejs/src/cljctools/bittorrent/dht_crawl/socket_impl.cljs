(ns cljctools.bittorrent.dht-crawl.socket-impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.spec.alpha :as s]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]

   [cljctools.bittorrent.dht-crawl.socket-spec :as socket-spec]
   [cljctools.bittorrent.dht-crawl.socket-protocols :as socket-protocols]))

(defonce bencode (js/require "bencode"))
(defonce dgram (js/require "dgram"))

(defn create
  [{:as opts
    :keys [::socket-spec/port
           ::socket-spec/host
           ::socket-spec/on-listening
           ::socket-spec/on-message
           ::socket-spec/on-error]
    :or {port 6881
         host "0.0.0.0"}}]
  {:post [(s/assert ::socket-spec/socket %)]}
  (let [stateA (atom {})
        socket-instance (.createSocket dgram "udp4")

        socket
        ^{:type ::socket-spec/socket}
        (reify
          socket-protocols/Socket
          (listen*
            [_]
            (.bind socket-instance port host))
          cljs.core/IDeref
          (-deref [_] @stateA))]

    (reset! stateA {:socket-instance socket-instance
                    :opts opts})

    (doto socket-instance
      (.on "listening" on-listening)
      (.on "message" on-message)
      (.on "error" on-error))

    socket))
