(ns cljctools.ipfs.node.core
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
   [cljctools.datagram-socket.core :as datagram-socket.core]))


#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(defprotocol Node)

(s/def ::node #(and
                (satisfies? Node %)
                #?(:clj (instance? clojure.lang.IDeref %))
                #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::create-node-opts (s/keys :req []
                                  :opt []))

(defn create-node
  [{:as opts
    :keys []}]
  {:pre [(s/assert ::create-node-opts opts)]
   :post [(s/assert ::node %)]}
  (go
    (let [stateV (volatile! {})

          node
          ^{:type ::node}
          (reify
            Node
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

      node)))


