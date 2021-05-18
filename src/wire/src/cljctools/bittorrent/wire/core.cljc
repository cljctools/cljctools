(ns cljctools.bittorrent.wire.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.bittorrent.bencode.core :as bencode.core]
   [cljctools.bittorrent.spec :as bittorrent.spec]))


(defprotocol Wire
  (handshake* [_ infohash peer-id])
  (request* [_ piece-index offset length]))

(defprotocol Extension
  (foo* [_]))

(s/def ::wire #(and
                (satisfies? Wire %)
                #?(:clj (satisfies? clojure.lang.IDeref %))
                #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::time-out int?)

(s/def ::create-wire-opts
  (s/keys :req []
          :opt [::time-out]))

(def ^:const ^bytes msg-protocol (bytes.core/to-bytes "\u0013BitTorrent protocol"))
(def ^:const ^bytes msg-keep-alive (bytes.core/bytes [0 0 0 0]))
(def ^:const ^bytes msg-choke (bytes.core/bytes [0 0 0 1 0]))
(def ^:const ^bytes msg-unchoke (bytes.core/bytes [0 0 0 1 1]))
(def ^:const ^bytes msg-interested (bytes.core/bytes [0 0 0 1 2]))
(def ^:const ^bytes msg-not-interested (bytes.core/bytes [0 0 0 1 3]))
(def ^:const ^bytes msg-have (bytes.core/bytes [0 0 0 5 4]))
(def ^:const ^bytes msg-reserved (bytes.core/bytes [0 0 0 0 0 0 0 0]))
(def ^:const ^bytes msg-port (bytes.core/bytes [0 0 0 3 9 0 0]))

(declare create-ut-metadata)

(defn create-wire
  [{:as opts
    :keys [::time-out]}]
  {:pre [(s/assert ::create-wire-opts opts)]
   :post [(s/assert ::wire %)]}
  (let [stateV (volatile!
                {:am-choking? true
                 :am-interested false
                 :peer-choking? true
                 :peer-interested? false
                 :extensions {}
                 :buffer []
                 :buffer-total-size 0
                 :uploaded 0
                 :downloaded 0
                 :peer-pieces (bytes.core/bitset 0 {:grow (* 50000 8)})})

        wire
        ^{:type ::wire}
        (reify
          Wire
          (handshake*
            [_])
          (request*
            [_])
          #?@(:clj
              [clojure.lang.IDeref
               (deref [_] @stateV)]
              :cljs
              [cljs.core/IDefef
               (-deref [_] @stateV)]))]

    (go
      (loop []
        (let [])))

    wire))

(defn create-ut-metadata
  [{:as opts
    :keys []}]
  (let [stateV (volatile!
                {})

        ut-metadata
        ^{:type ::wire}
        (reify
          Extension
          (foo* [_])


          #?@(:clj
              [clojure.lang.IDeref
               (deref [_] @stateV)]
              :cljs
              [cljs.core/IDefef
               (-deref [_] @stateV)]))]

    (go
      (loop []
        (let [])))

    ut-metadata))


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