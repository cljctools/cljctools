(ns cljctools.ipfs.runtime.dht
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]

   [cljctools.socket.protocols :as socket.protocols]
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.runtime.core :as socket.runtime.core]

   [cljctools.ipfs.spec :as ipfs.spec]
   [cljctools.ipfs.protocols :as ipfs.protocols]
   [cljctools.ipfs.runtime.impl :as ipfs.runtime.impl])
  (:import
   (io.libp2p.core Connection Host PeerId)
   (io.libp2p.core.multiformats Multiaddr)
   (io.libp2p.pubsub.gossip Gossip)
   (io.libp2p.core.multistream  ProtocolBinding StrictProtocolBinding)
   (io.libp2p.protocol Ping)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))


(defn create
  [{:as opts
    :keys []}]

  (go
    (let [bootstrap-multiaddresses
          ["/dnsaddr/bootstrap.libp2p.io/p2p/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN"
           "/dnsaddr/bootstrap.libp2p.io/p2p/QmQCU2EcMqAqQPR2i9bChDtGNJchTbq5TbXJJ16u19uLTa"
           "/dnsaddr/bootstrap.libp2p.io/p2p/QmbLHAnMoJPWSCR5Zhtx6BHJX9KiKNN6tpvbUcqanj75Nb"
           "/dnsaddr/bootstrap.libp2p.io/p2p/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt"
           #_"/dns4/node0.preload.ipfs.io/tcp/443/wss/p2p/QmZMxNdpMkewiVZLMRxaNxUeZpDUb34pWjZ1kZvsd16Zic"
           #_"/dns4/node1.preload.ipfs.io/tcp/443/wss/p2p/Qmbut9Ywz9YEDrz8ySBSgWyJk41Uvm2QJPhwDJzJyGFsD6"
           #_"/dns4/node2.preload.ipfs.io/tcp/443/wss/p2p/QmV7gnbW5VTcJ3oyM2Xk1rdFBJ3kTkvxc87UFGsun29STS"
           #_"/dns4/node3.preload.ipfs.io/tcp/443/wss/p2p/QmY7JB6MQXhxHvq7dBDh4HpbH29v4yE9JRadAVpndvzySN"
           "/ip4/104.131.131.82/tcp/4001/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"
           "/ip4/104.131.131.82/udp/4001/quic/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"]

          ping-protocol (Ping.)
          dht-protocol (ipfs.runtime.impl/create-dht-protocol)
          gossip-potocol (Gossip.)
          host (ipfs.runtime.impl/create-host [ping-protocol dht-protocol gossip-potocol])]

      (go
        (loop []))

      (go
        (loop [])))))