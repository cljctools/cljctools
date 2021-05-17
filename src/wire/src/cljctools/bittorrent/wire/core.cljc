(ns cljctools.bittorrent.wire.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljctools.bittorrent.bencode.core :as bencode.core]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]))

(def ^:const ^bytes msg-protocol (bytes.core/to-bytes "\u0013BitTorrent protocol"))
(def ^:const ^bytes msg-keep-alive (bytes.core/bytes [0 0 0 0]))
(def ^:const ^bytes msg-choke (bytes.core/bytes [0 0 0 1 0]))
(def ^:const ^bytes msg-unchoke (bytes.core/bytes [0 0 0 1 1]))
(def ^:const ^bytes msg-interested (bytes.core/bytes [0 0 0 1 2]))
(def ^:const ^bytes msg-not-interested (bytes.core/bytes [0 0 0 1 3]))
(def ^:const ^bytes msg-have (bytes.core/bytes [0 0 0 5 4]))


(comment

  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.bittorrent/wire {:local/root "./bittorrent/src/wire"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}
                      github.cljctools/core-jvm {:local/root "./cljctools/src/core-jvm"}}}'
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])

    (require '[cljctools.bytes.core :as bytes.core]))
  

   clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                       org.clojure/core.async {:mvn/version "1.3.618"}
                       github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                       github.cljctools.bittorrent/wire {:local/root "./bittorrent/src/wire"}
                       github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}
                       github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}
                       github.cljctools/core-js {:local/root "./cljctools/src/core-js"}}}' \
   -M -m cljs.main --repl-env node --watch "bittorrent/src/wire" --compile cljctools.bittorrent.wire.core --repl
  
  (require '[cljctools.bytes.core :as bytes.core])
  (require '[cljctools.bittorrent.wire.core :as wire.core])
  
  ;
  )