(ns ipfs-shipyard.find.ipfs-dht
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]

   [cljctools.ipfs.spec :as ipfs.spec]
   [cljctools.ipfs.protocols :as ipfs.protocols]
   [cljctools.ipfs.runtime.impl :as ipfs.runtime.impl])
  (:import
   (io.libp2p.core Connection Host PeerId Stream)
   (io.libp2p.core.dsl HostBuilder)
   (io.libp2p.security.noise NoiseXXSecureChannel)
   (io.libp2p.core.multiformats Multiaddr Protocol)
   (io.libp2p.core.multistream  ProtocolBinding StrictProtocolBinding)
   (io.libp2p.protocol Ping PingController)
   (io.libp2p.etc.encode Base58)
   (io.netty.buffer ByteBuf ByteBufUtil Unpooled)
   (java.util.function Function Consumer)
   (kotlin.jvm.functions Function1)
   (java.util.concurrent CompletableFuture TimeUnit)
   (com.google.protobuf ByteString)
   (cljctools.ipfs.runtime DhtProto$DhtMessage DhtProto$DhtMessage$Type DhtProto$DhtMessage$Peer)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn start-find-nodes
  [{:as opts
    :keys [dht
           stop|]}]
  (go
    (loop [timeout| (timeout 0)]
      (alt!
        timeout|
        ([_]
         (->>
          (Multiaddr/fromString "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ")
          (ipfs.protocols/find-node* dht))
         (recur (timeout (* 3 60 1000))))

        stop|
        ([_]
         (do :stop))))))

(defn create
  [{:as opts
    :keys []
    :or {}}]
  (let [bootstrap-multiaddresses
        ["/dnsaddr/bootstrap.libp2p.io/ipfs/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN"
         "/dnsaddr/bootstrap.libp2p.io/ipfs/QmQCU2EcMqAqQPR2i9bChDtGNJchTbq5TbXJJ16u19uLTa"
         "/dnsaddr/bootstrap.libp2p.io/ipfs/QmbLHAnMoJPWSCR5Zhtx6BHJX9KiKNN6tpvbUcqanj75Nb"
         "/dnsaddr/bootstrap.libp2p.io/ipfs/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt"
         #_"/dns4/node0.preload.ipfs.io/tcp/443/wss/p2p/QmZMxNdpMkewiVZLMRxaNxUeZpDUb34pWjZ1kZvsd16Zic"
         #_"/dns4/node1.preload.ipfs.io/tcp/443/wss/p2p/Qmbut9Ywz9YEDrz8ySBSgWyJk41Uvm2QJPhwDJzJyGFsD6"
         #_"/dns4/node2.preload.ipfs.io/tcp/443/wss/p2p/QmV7gnbW5VTcJ3oyM2Xk1rdFBJ3kTkvxc87UFGsun29STS"
         #_"/dns4/node3.preload.ipfs.io/tcp/443/wss/p2p/QmY7JB6MQXhxHvq7dBDh4HpbH29v4yE9JRadAVpndvzySN"
         "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"
         "/ip4/104.131.131.82/udp/4001/quic/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"]

        msg-dht| (chan (sliding-buffer 1000))

        ping-protocol (Ping.)
        dht-protocol (ipfs.runtime.impl/create-dht-protocol
                      {:on-message
                       (fn [stream msg]
                         (put! msg-dht| {:msg msg
                                         :peer-id (-> ^Stream stream (.remotePeerId))}))})
        host (->
              (HostBuilder.)
              (.protocol (into-array ProtocolBinding [ping-protocol dht-protocol]))
              (.secureChannel
               (into-array Function [(reify Function
                                       (apply
                                         [_ priv-key]
                                         (NoiseXXSecureChannel. ^PrivKey priv-key)))]))
              (.listen (into-array String ["/ip4/127.0.0.1/tcp/0"]))
              (.build))

        host-peer-id (.getPeerId host)
        host-peer-idS (.toString host-peer-id)
        host-priv-key (.getPrivKey host)

        subscriptionsA (atom {})
        connectionsA (atom {})

        peersA (atom {})

        stop-channelsA (atom [])

        stateA (atom
                {:host host
                 :host-peer-id host-peer-id
                 :host-peer-idS host-peer-idS
                 :ping-protocol ping-protocol
                 :dht-protocol dht-protocol
                 :connectionsA connectionsA
                 :peersA peersA})

        dht
        ^{:type ::ipfs.spec/dht}
        (reify
          ipfs.protocols/Connect
          (connect*
            [t multiaddr]
            (ipfs.protocols/connect* t (.getFirst (.toPeerIdAndAddr ^Multiaddr multiaddr)) [(.getSecond (.toPeerIdAndAddr ^Multiaddr multiaddr))]))
          (connect*
            [_ peer-id multiaddrs]
            (let [peer-id-string (.toString ^PeerId peer-id)
                  out| (chan 1)]
              (->
               (-> host (.getNetwork) (.connect ^PeerId peer-id (into-array Multiaddr multiaddrs)))
               (ipfs.runtime.impl/cfuture-to-channel (timeout 2000))
               (take! (fn [connection]
                        (when connection
                          (-> (.closeFuture ^Connection connection)
                              (.thenApply (reify Function
                                            (apply [_ _]
                                              (swap! connectionsA dissoc peer-id-string)))))
                          (swap! connectionsA assoc peer-id-string connection)
                          (put! out| connection))
                        (close! out|))))
              out|))
          ipfs.protocols/Disconnect
          (disconnect*
            [_ multiaddr]
            (let [peer-id-string (-> ^Multiaddr multiaddr (.toPeerIdAndAddr) (.getFirst) (.toString))]
              (when-let [^Connection connection (get @connectionsA peer-id-string)]
                (.close connection))))
          ipfs.protocols/Dht
          (get-peer-id*
            [_]
            (.getPeerId host))
          (get-listen-multiaddrs*
            [_]
            (.listenAddresses host))
          (ping*
            [t multiaddr]
            (go
              (when (<! (ipfs.protocols/connect* t multiaddr))
                (let [^PingController pinger (-> ping-protocol (.dial host ^Multiaddr multiaddr) (.getController) (.get 5 TimeUnit/SECONDS))]
                  (dotimes [i 1]
                    (let [latency (-> pinger (.ping) (.get 2 TimeUnit/SECONDS))]
                      (println latency)))))))
          (find-node*
            [t multiaddr]
            (ipfs.protocols/send*
             t
             multiaddr
             (-> (DhtProto$DhtMessage/newBuilder)
                 (.setType DhtProto$DhtMessage$Type/FIND_NODE)
                 (.setKey (->
                           host-peer-idS
                           (io.ipfs.multihash.Multihash/fromBase58)
                           (.toBytes)
                           (ByteString/copyFrom)))
                 (.build))))
          ipfs.protocols/Send
          (send*
            [t multiaddr msg]
            (go
              (when (<! (ipfs.protocols/connect* t multiaddr))
                (let [dht-controller (-> dht-protocol (.dial host ^Multiaddr multiaddr) (.getController) (.get 2 TimeUnit/SECONDS))]
                  (ipfs.runtime.impl/send* dht-controller msg)))))
          ipfs.protocols/Release
          (release*
            [_]
            (doseq [stop| @stop-channelsA]
              (close! stop|))
            (-> host (.stop) (.get 2 TimeUnit/SECONDS)))
          clojure.lang.IDeref
          (deref [_] @stateA))]

    (go
      (loop []
        (when-let [{:keys [^DhtProto$DhtMessage msg ^PeerId peer-id]} (<! msg-dht|)]
          (when-not (.isEmpty (.getCloserPeersList msg))
            (let [peers (into {}
                              (map (fn [^DhtProto$DhtMessage$Peer peer]
                                     (let [multiaddrs (map (fn [^ByteString bytestring]
                                                             (Multiaddr. (-> bytestring (.toByteArray)))) (.getAddrsList peer))
                                           peer-id (-> peer (.getId) (.toByteArray) (PeerId.))
                                           peer-idS (.toString peer-id)]
                                       [peer-idS {:peer-id peer-id
                                                  :multiaddrs multiaddrs}])))
                              (vec (.getCloserPeersList msg)))]
              (swap! peersA merge peers)
              (->>
               peers
               (map (fn [[peer-idS {:keys [peer-id multiaddrs]}]]
                      (ipfs.protocols/connect* dht peer-id multiaddrs)))
               (a/map identity)
               (<!))
              (println :total-connections (-> host (.getNetwork) (.getConnections) (vec) (count)))))
          (recur))))

    (go
      (-> host (.start) (.get 2 TimeUnit/SECONDS))
      (println (format "host listening on \n %s" (.listenAddresses host)))

      (let [stop| (chan 1)]
        (swap! stop-channelsA conj stop|)
        #_(start-find-nodes {:dht dht
                             :stop| stop|}))
      dht)))


(comment

  (require
   '[cljctools.ipfs.runtime.dht :as ipfs.runtime.dht]
   :reload)


  ;
  )