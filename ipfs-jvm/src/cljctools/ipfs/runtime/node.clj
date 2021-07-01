(ns cljctools.ipfs.runtime.node
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
   (io.libp2p.pubsub.gossip Gossip GossipRouter GossipParams GossipScoreParams GossipPeerScoreParams)
   (io.libp2p.pubsub.gossip.builders GossipParamsBuilder GossipScoreParamsBuilder GossipPeerScoreParamsBuilder)
   (io.libp2p.pubsub PubsubApiImpl)
   (io.libp2p.core.pubsub PubsubSubscription Topic MessageApi)
   (io.libp2p.core.multistream  ProtocolBinding StrictProtocolBinding)
   (io.libp2p.protocol Ping PingController)
   (io.libp2p.etc.encode Base58)
   (io.netty.buffer ByteBuf ByteBufUtil Unpooled)
   (java.util.function Function Consumer)
   (kotlin.jvm.functions Function1)
   (java.util.concurrent CompletableFuture TimeUnit)
   (com.google.protobuf ByteString)
   (cljctools.ipfs.runtime NodeProto$DhtMessage NodeProto$DhtMessage$Type NodeProto$DhtMessage$Peer)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn start-find-node
  [{:as opts
    :keys [node
           stop|]}]
  (go
    (loop [timeout| (timeout 0)]
      (alt!
        timeout|
        ([_]
         (->>
          (Multiaddr/fromString "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ")
          (ipfs.protocols/find-node* node))
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
        gossip-router (GossipRouter.
                       (->
                        (GossipParamsBuilder.)
                        (.floodPublish true)
                        (.build))
                       (->
                        (GossipScoreParamsBuilder. (GossipScoreParams.))
                        (.peerScoreParams (->
                                           (GossipPeerScoreParamsBuilder. (GossipPeerScoreParams.))
                                           (.isDirect (reify
                                                        Function1
                                                        (invoke [_ peer-handler]
                                                          true)))
                                           (.build)))
                        (.gossipThreshold 0.0)
                        (.publishThreshold 0.0)
                        (.build)))
        pubsub-api (PubsubApiImpl. gossip-router)
        gossip-protocol (Gossip. gossip-router pubsub-api)
        host (->
              (HostBuilder.)
              (.protocol (into-array ProtocolBinding [ping-protocol dht-protocol gossip-protocol]))
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

        publisher (.createPublisher gossip-protocol (.getPrivKey host)  1234)

        subscriptionsA (atom {})
        connectionsA (atom {})

        peersA (atom {})

        stop-channelsA (atom [])

        stateA (atom
                {:host host
                 :host-peer-id host-peer-id
                 :host-peer-idS host-peer-idS
                 :publisher publisher
                 :ping-protocol ping-protocol
                 :gossip-router gossip-router
                 :pubsub-api pubsub-api
                 :gossip-protocol gossip-protocol
                 :dht-protocol dht-protocol
                 :connectionsA connectionsA
                 :peersA peersA})

        node
        ^{:type ::ipfs.spec/node}
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
          ipfs.protocols/Node
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
          (send-dht*
            [t multiaddr msg]
            (go
              (when (<! (ipfs.protocols/connect* t multiaddr))
                (let [dht-controller (-> dht-protocol (.dial host ^Multiaddr multiaddr) (.getController) (.get 2 TimeUnit/SECONDS))]
                  (ipfs.runtime.impl/send* dht-controller msg)))))
          (find-node*
            [t multiaddr]
            (ipfs.protocols/send-dht*
             t
             multiaddr
             (-> (NodeProto$DhtMessage/newBuilder)
                 (.setType NodeProto$DhtMessage$Type/FIND_NODE)
                 (.setKey (->
                           host-peer-idS
                           (io.ipfs.multihash.Multihash/fromBase58)
                           (.toBytes)
                           (ByteString/copyFrom)))
                 (.build))))
          (subscribe*
            [_ topic on-message]
            (let [subscription
                  (.subscribe gossip-protocol
                              ^Consumer
                              (reify Consumer
                                (accept
                                  [_ msg]
                                  (let [msg ^MessageApi msg
                                        value {:dataBA (-> msg (.getData)
                                                           (ipfs.runtime.impl/to-byte-array))
                                               :from (PeerId. (.getFrom msg))
                                               :topics (.getTopics msg)}]
                                    (println :ref-cnt (-> msg (.getData) (.refCnt)))
                                    (on-message value))))
                              ^"[Lio.libp2p.core.pubsub.Topic;"
                              (into-array Topic [(Topic. topic)]))]
              (swap! subscriptionsA assoc topic subscription)))
          (unsubscribe*
            [_ topic]
            (when-let [subscription (get @subscriptionsA topic)]
              (.unsubscribe ^PubsubSubscription subscription)
              (swap! subscriptionsA dissoc topic)))
          (publish*
            [_ topic msg-string]
            (.publish publisher
                      (-> ^String msg-string (.getBytes "UTF-8") (Unpooled/wrappedBuffer))
                      (into-array Topic [(Topic. topic)])))
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
        (when-let [{:keys [^NodeProto$DhtMessage msg ^PeerId peer-id]} (<! msg-dht|)]
          (when-not (.isEmpty (.getCloserPeersList msg))
            (let [peers (into {}
                              (map (fn [^NodeProto$DhtMessage$Peer peer]
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
                      (ipfs.protocols/connect* node peer-id multiaddrs)))
               (a/map identity)
               (<!))
              (println :total-connections (-> host (.getNetwork) (.getConnections) (vec) (count)))))
          (recur))))

    (go
      (-> host (.start) (.get 2 TimeUnit/SECONDS))
      (println (format "host listening on \n %s" (.listenAddresses host)))

      (let [stop| (chan 1)]
        (swap! stop-channelsA conj stop|)
        #_(start-find-node {:node node
                            :stop| stop|}))
      node)))