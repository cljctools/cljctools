(ns cljctools.ipfs.dht.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]

   [cljctools.socket.protocols :as socket.protocols]
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.impl :as socket.impl]

   [cljctools.ipfs.spec :as ipfs.spec]
   [cljctools.ipfs.dht.wire :as dht.wire]
   [cljctools.ipfs.dht.impl :refer [multiaddress-to-data]]))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(declare
 connect)

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
           "/ip4/104.131.131.82/udp/4001/quic/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"]]

      (go
        (loop []))

      (go
        (loop [])))))


(defn connect
  [{:as opts
    :keys [::ipfs.spec/multiaddress
           ::send|
           ::msg|
           ::ex|]}]
  (go
    (let [socket-msg| (chan 100)
          socket-evt| (chan (sliding-buffer 10))
          socket-ex| (chan 1)

          {:keys [::ipfs.spec/host
                  ::ipfs.spec/port]} (multiaddress-to-data multiaddress)
          socket (socket.impl/create
                  {::socket.spec/port port
                   ::socket.spec/host host
                   ::socket.spec/evt| socket-evt|
                   ::socket.spec/msg| socket-msg|
                   ::socket.spec/ex| socket-ex|})

          release (fn []
                    (socket.protocols/close* socket)
                    (close! socket-msg|)
                    (close! socket-evt|))]

      (dht.wire/create {::recv| socket-msg|
                        ::send| send|
                        ::msg| msg|})

      (go
        (when-let [evt (<! socket-evt|)]
          #_(println ::socket evt))
        (loop []
          (alt!
            socket-ex|
            ([ex]
             (when ex
               (>! ex| ex)))

            send|
            ([value]
             (when value
               (socket.protocols/send* socket value)
               (recur)))
            :priority true))))))