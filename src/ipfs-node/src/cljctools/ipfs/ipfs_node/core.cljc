(ns cljctools.ipfs.ipfs-node.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljctools.socket.protocols :as socket.protocols]
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.core :as socket.core]

   [cljctools.datagram-socket.protocols :as datagram-socket.protocols]
   [cljctools.datagram-socket.spec :as datagram-socket.spec]
   [cljctools.datagram-socket.core :as datagram-socket.core]

   [cljctools.ipfs.dht.core :as dht.core]))


#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(defprotocol IpfsNode)

(s/def ::ipfs-node #(and
                     (satisfies? IpfsNode %)
                     #?(:clj (instance? clojure.lang.IDeref %))
                     #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::opts (s/keys :req []
                      :opt []))

(defn create
  [{:as opts
    :keys []}]
  {:pre [(s/assert ::opts opts)]
   :post [(s/assert ::ipfs-node %)]}
  (go
    (let [stateV (volatile! {})

          ipfs-node
          ^{:type ::ipfs-node}
          (reify
            IpfsNode
            #?@(:clj
                [clojure.lang.IDeref
                 (deref [_] @stateV)]
                :cljs
                [cljs.core/IDeref
                 (-deref [_] @stateV)]))]

      (go
        (loop []))

      (go
        (loop []))

      ipfs-node)))