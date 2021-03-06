(ns cljctools.ipfs.runtime.core)

(comment

  (require
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.codec.runtime.core :as codec.runtime.core]
   '[cljctools.ipfs.protocols :as ipfs.protocols]
   '[cljctools.ipfs.spec :as ipfs.spec]
   '[cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto]
   '[cljctools.ipfs.runtime.impl :as ipfs.runtime.impl]
   '[cljctools.ipfs.runtime.core :as ipfs.runtime.core]
   '[cljctools.ipfs.runtime.node :as ipfs.runtime.node]
   '[cljctools.ipfs.runtime.core :as ipfs.runtime.core]
   :reload)

  (do
    (def key-pair
      #_(ipfs.runtime.crypto/generate-keypair ::ipfs.spec/RSA 2048)
      (ipfs.runtime.crypto/generate-keypair ::ipfs.spec/Ed25519))
    (def private-key (::ipfs.spec/private-key key-pair))
    (def public-key (::ipfs.spec/public-key key-pair))
    (def peer-id (ipfs.runtime.core/create-peer-id public-key))
    (ipfs.protocols/to-string* peer-id))

  (do
    (def public-keyBA (-> public-key (ipfs.runtime.crypto/protobuf-encode-public-key)))
    (def multihash (if (<= (alength public-keyBA) 42)
                     (io.ipfs.multihash.Multihash. io.ipfs.multihash.Multihash$Type/id public-keyBA)
                     (io.ipfs.multihash.Multihash. io.ipfs.multihash.Multihash$Type/sha2_256 (ipfs.runtime.crypto/sha2-256 public-keyBA))))
    (def cid (io.ipfs.cid.Cid/buildCidV1 io.ipfs.cid.Cid$Codec/Libp2pKey (.getType multihash) (.getHash multihash)))

    (-> (.toString cid)
        (io.ipfs.cid.Cid/decode)
        (.getHash)
        (ipfs.runtime.core/encode-multihash)
        (ipfs.runtime.core/create-peer-id)
        (ipfs.protocols/to-string*)
        (= (.toString multihash))))

  ; rsa
  ; "QmSZbRqsU9b776LcUDweHdCLs2hvaf78hWiCYwPYS89FNz"
  (map #(aget peer-idBA %) (range 0 10)) ; 18 32 

  ; ed25519
  ; "12D3KooWLfSxx6bSHcmRhcjGcLab4SuMvAbL7qVuQoM1xJJsPExD"
  ; 0 first

  (-> "12D3KooWQGcNmEMGBT1gXmLraNDZVPiv3GVf3WhrDiJAokRQ6Sqg"
      (ipfs.runtime.core/create-peer-id)
      (ipfs.protocols/to-byte-array*)
      (ipfs.runtime.core/decode-multihash)
      (ipfs.runtime.crypto/protobuf-decode-public-key)
      (ipfs.runtime.core/create-peer-id)
      (ipfs.protocols/to-string*))

  (-> "12D3KooWQGcNmEMGBT1gXmLraNDZVPiv3GVf3WhrDiJAokRQ6Sqg"
      (io.ipfs.multihash.Multihash/fromBase58)
      (.getHash)
      (ipfs.runtime.crypto/protobuf-decode-public-key)
      (ipfs.runtime.core/create-peer-id)
      (ipfs.protocols/to-string*))

  ;
  )

(comment

  (let [id1 "12D3KooWGDYpB839K6f12Z49qayjZbwBAYXFuDYSMEDJs7dwmD4c"
        id2 "12D3KooWMCS4kKTbAsJ6pzPFpNopzCdftMQ9Nm9dFbWMJNqrWA7i"]
    (time
     (dotimes [i 10000000]
       (= id1 id2))))
  ; "Elapsed time: 801.452481 msecs"

  (let [id1BA (-> "12D3KooWGDYpB839K6f12Z49qayjZbwBAYXFuDYSMEDJs7dwmD4c"
                  (ipfs.runtime.core/create-peer-id)
                  (ipfs.protocols/to-byte-array*))
        id2BA (-> "12D3KooWMCS4kKTbAsJ6pzPFpNopzCdftMQ9Nm9dFbWMJNqrWA7i"
                  (ipfs.runtime.core/create-peer-id)
                  (ipfs.protocols/to-byte-array*))]
    (time
     (dotimes [i 10000000]
       (bytes.runtime.core/equals? id1BA id2BA))))
  ; "Elapsed time: 1315.877881 msecs"

  ;
  )

(comment

  (do
    (require
     '[clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! do-alts alt! alts! close!
                                        pub sub unsub mult tap untap mix admix unmix pipe
                                        timeout to-chan  sliding-buffer dropping-buffer
                                        pipeline pipeline-async]]
     '[cljctools.bytes.runtime.core :as bytes.runtime.core]
     '[cljctools.codec.runtime.core :as codec.runtime.core]
     '[cljctools.ipfs.protocols :as ipfs.protocols]
     '[cljctools.ipfs.spec :as ipfs.spec]
     '[cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto]
     '[cljctools.ipfs.runtime.impl :as ipfs.runtime.impl]
     '[cljctools.ipfs.runtime.core :as ipfs.runtime.core]
     '[cljctools.ipfs.runtime.node :as ipfs.runtime.node]
     '[cljctools.ipfs.runtime.core :as ipfs.runtime.core]
     :reload)
    (import
     '(io.libp2p.core Connection Host PeerId Stream)
     '(io.libp2p.core.dsl HostBuilder)
     '(java.net InetAddress InetSocketAddress)
     '(io.libp2p.core.multiformats Multiaddr MultiaddrDns Protocol)
     '(io.libp2p.core Libp2pException Stream P2PChannelHandler)
     '(io.libp2p.protocol Ping
                          PingProtocol PingController ProtocolHandler ProtobufProtocolHandler
                          ProtocolMessageHandler ProtocolMessageHandler$DefaultImpls)
     '(java.util.function Function Consumer)
     '(io.netty.buffer ByteBuf ByteBufUtil Unpooled)
     '(java.util.concurrent CompletableFuture TimeUnit)
     '(io.libp2p.security.noise NoiseXXSecureChannel)
     '(io.libp2p.core.multistream ProtocolBinding StrictProtocolBinding ProtocolDescriptor)
     '(io.libp2p.core.crypto PrivKey)
     '(io.libp2p.pubsub.gossip Gossip GossipRouter GossipParams GossipScoreParams GossipPeerScoreParams)
     '(io.libp2p.pubsub.gossip.builders GossipParamsBuilder GossipScoreParamsBuilder GossipPeerScoreParamsBuilder)
     '(io.libp2p.pubsub PubsubApiImpl)
     '(io.libp2p.etc.encode Base58)
     '(io.libp2p.etc.util P2PService$PeerHandler)
     '(io.libp2p.core.pubsub Topic MessageApi)
     '(io.libp2p.discovery MDnsDiscovery)
     '(kotlin.jvm.functions Function1)
     '(com.google.protobuf ByteString)
     '(cljctools.ipfs.runtime DhtProto$DhtMessage DhtProto$DhtMessage$Type DhtProto$DhtMessage$Peer)))

  (do
    (def ping (Ping.))
    (def dht-protocol (ipfs.runtime.impl/create-dht-protocol))
    (def host (->
               (HostBuilder.)
               (.protocol (into-array ProtocolBinding [ping dht-protocol]))
               (.secureChannel
                (into-array Function [(reify Function
                                        (apply
                                          [_ priv-key]
                                          (NoiseXXSecureChannel. ^PrivKey priv-key)))]))
               (.listen (into-array String ["/ip4/127.0.0.1/tcp/0"]))
               (.build)))
    (-> host (.start) (.get))
    (println (format "host listening on \n %s" (.listenAddresses host))))

  (do
    (def address (Multiaddr/fromString "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"))
    (def pinger (-> ping (.dial host address) (.getController) (.get 5 TimeUnit/SECONDS)))
    (dotimes [i 5]
      (let [latency (-> pinger (.ping) (.get 5 TimeUnit/SECONDS))]
        (println latency))))

  (.stop host)

  (def dht-controller (-> dht-protocol (.dial host address) (.getController) (.get 5 TimeUnit/SECONDS)))

  (ipfs.runtime.impl/send* dht-controller
                           (-> (DhtProto$DhtMessage/newBuilder)
                               (.setType DhtProto$DhtMessage$Type/FIND_NODE)
                               (.setKey (->
                                         "12D3KooWQGcNmEMGBT1gXmLraNDZVPiv3GVf3WhrDiJAokRQ6Sqg"
                                         (io.ipfs.multihash.Multihash/fromBase58)
                                         (.toBytes)
                                         (ByteString/copyFrom)))
                               (.build)))

  (StrictProtocolBinding. "/ipfs/ping/1.0.0" (PingProtocol.))
  (ProtocolDescriptor. "/ipfs/ping/1.0.0")
  (def strict-protocol-binding (proxy [StrictProtocolBinding] ["/ipfs/ping/1.0.0" (PingProtocol.)]))
  (instance? StrictProtocolBinding strict-protocol-binding)

  (def protocol-handler (proxy [ProtocolHandler] [1000 1000]))
  (.initProtocolStream protocol-handler (reify Stream))


  (instance? P2PChannelHandler (PingProtocol.))
  (instance? ProtocolHandler (PingProtocol.))

  (java.lang.reflect.Modifier/isAbstract (.getModifiers ProtocolMessageHandler))
  (java.lang.reflect.Modifier/isInterface (.getModifiers ProtocolMessageHandler))
  (java.lang.reflect.Modifier/isInterface (.getModifiers ProtocolBinding))

  io.libp2p.protocol.ProtocolMessageHandler$DefaultImpls


  ;
  )


(comment

  (do
    (defn start-host
      [gossip]
      (let [host (->
                  (HostBuilder.)
                  (.protocol (into-array ProtocolBinding [(Ping.) gossip (ipfs.runtime.impl/create-dht-protocol {:on-message (fn [msg])})]))
                  (.secureChannel
                   (into-array Function [(reify Function
                                           (apply
                                             [_ priv-key]
                                             (NoiseXXSecureChannel. ^PrivKey priv-key)))]))
                  (.listen (into-array String ["/ip4/127.0.0.1/tcp/0"]))
                  (.build))]
        (-> host (.start) (.get))
        (println (format "host listening on \n %s" (.listenAddresses host)))
        host))

    (defn connect
      ([host multiaddr]
       (connect host (.getFirst (.toPeerIdAndAddr multiaddr)) [(.getSecond (.toPeerIdAndAddr multiaddr))]))
      ([host peer-id multiaddrs]
       (->
        host
        (.getNetwork)
        (.connect ^PeerId peer-id (into-array Multiaddr multiaddrs))
        (.thenAccept (reify Consumer
                       (accept [_ connection]
                         (println ::connected connection)))))))

    (defn ping
      [host address]
      (let [pinger (-> (Ping.) (.dial host address) (.getController) (.get 5 TimeUnit/SECONDS))]
        (dotimes [i 5]
          (let [latency (-> pinger (.ping) (.get 5 TimeUnit/SECONDS))]
            (println latency)))))

    (defn subsribe
      [gossip key topic]
      (.subscribe gossip
                  (reify Consumer
                    (accept [_ msg]
                      (println ::gossip-recv-msg key (-> ^MessageApi msg
                                                         (.getData)
                                                         (ipfs.runtime.impl/to-byte-array)
                                                         (bytes.runtime.core/to-string)))))
                  (into-array Topic [(Topic. topic)])))

    (defn publish
      [publisher topic string]
      (.publish publisher (Unpooled/wrappedBuffer (.getBytes string "UTF-8")) (into-array Topic [(Topic. topic)])))

    (defn linst-methods
      [v]
      (->> v
           clojure.reflect/reflect
           :members
           (filter #(contains? (:flags %) :public))
           (filter #(or (instance? clojure.reflect.Method %)
                        (instance? clojure.reflect.Constructor %)))
           (sort-by :name)
           (map #(select-keys % [:name :return-type :parameter-types]))
           clojure.pprint/print-table)))


  (do
    (def address1 (Multiaddr/fromString "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"))
    (def address2 (Multiaddr/fromString "/dnsaddr/bootstrap.libp2p.io/ipfs/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN"))
    (def address21 (Multiaddr/fromString "/ip4/147.75.109.213/tcp/4001/ipfs/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN"))
    (def address3 (Multiaddr/fromString "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ")))

  (do
    (def gossip1 (Gossip.))
    (def host1 (start-host gossip1))

    (def gossip2 (Gossip.))
    (def host2 (start-host gossip2))

    (def gossip3 (Gossip.))
    (def host3 (start-host gossip3)))

  (.getPeerId host1)
  (.listenAddresses host1)

  (do
    (def publisher1 (.createPublisher gossip1 (.getPrivKey host1) 1234))
    (def publisher2 (.createPublisher gossip2 (.getPrivKey host2) 1234))
    (def publisher3 (.createPublisher gossip3 (.getPrivKey host3) 1234)))

  (connect host1 (.getPeerId host2) (.listenAddresses host2))

  (do
    (connect host1 (.getPeerId host3) (.listenAddresses host3))
    (connect host2 (.getPeerId host3) (.listenAddresses host3)))

  (subsribe gossip1  :gossip1  "topic1")
  (subsribe gossip2  :gossip2  "topic1")


  (publish publisher1 "topic1" "from publisher1")
  (publish publisher2 "topic1" "from publisher2")


  (do
    (.stop host1) (.stop host2) (.stop host3))

  (connect host1 address3)
  (connect host2 address3)

  (.toString (.getSecond (.toPeerIdAndAddr address2)))

  (MultiaddrDns/resolve address2)

  (-> MultiaddrDns (clojure.reflect/reflect) (clojure.pprint/pprint))
  (-> io.libp2p.core.multiformats.MultiaddrDns$Companion (clojure.reflect/reflect) (clojure.pprint/pprint))

  (->>
   (InetAddress/getAllByName "libp2p.io")
   (filter (fn [inet-addr] (instance? java.net.Inet4Address inet-addr)))
   (map (fn [inet-addr] (.getHostAddress ^InetAddress inet-addr))))

  host -t TXT _dnsaddr.bootstrap.libp2p.io
  nslookup sjc-2.bootstrap.libp2p.io
  ; ewr-1.bootstrap.libp2p.io 147.75.77.187
  ; ams-rust.bootstrap.libp2p.io 145.40.68.179
  ; ams-2.bootstrap.libp2p.io 147.75.83.83
  ; nrt-1.bootstrap.libp2p.io 147.75.94.115
  ; sjc-2.bootstrap.libp2p.io 147.75.109.29
  ; sjc-1.bootstrap.libp2p.io 147.75.109.213


  (ping host1 address3)
  (ping host1 address21)


  ;
  )

(comment


  (def cf (java.util.concurrent.CompletableFuture.))

  (.thenApply cf (reify Function
                   (apply [_ result]
                     (println :complete result))))

  (.complete cf 3)

  (dotimes [i 1000000]
    (let [cf (java.util.concurrent.CompletableFuture.)]
      (.thenApply cf (reify Function
                       (apply [_ result]
                         (println :complete result))))))

  ;
  )

(comment

  (do
    (def node1 (a/<!! (ipfs.runtime.node/create {})))
    (def node2 (a/<!! (ipfs.runtime.node/create {})))
    (def node3 (a/<!! (ipfs.runtime.node/create {}))))

  (->>
   (Multiaddr/fromString "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ")
   (ipfs.protocols/find-node* node1))

  (-> @node1 :host (.getNetwork) (.getConnections) (vec) (count))
  (-> @node1 :gossip-protocol (.getPeerTopics) (.get))

  (go
    (->>
     (-> @node1 :host (.getNetwork) (.getConnections) (vec))
     (map (fn [connection]
            (ipfs.protocols/connect* node2 (Multiaddr.  (.remoteAddress connection) (-> connection (.secureSession) (.getRemoteId))))))
     (a/map vector)
     (<!))
    (println :total-connections (-> @node2 :host (.getNetwork) (.getConnections) (vec) (count))))

  (def connection (-> @node1 :host (.getNetwork) (.getConnections) (vec) (first)))
  (Multiaddr.  (.remoteAddress connection) (-> connection (.secureSession) (.getRemoteId)))
  (-> connection (.secureSession) (type))

  (ipfs.protocols/connect* node1 (ipfs.protocols/get-peer-id* node2) (ipfs.protocols/get-listen-multiaddrs* node2))

  (do
    (ipfs.protocols/connect* node1 (ipfs.protocols/get-peer-id* node3) (ipfs.protocols/get-listen-multiaddrs* node3))
    (ipfs.protocols/connect* node2 (ipfs.protocols/get-peer-id* node3) (ipfs.protocols/get-listen-multiaddrs* node3)))

  (ipfs.protocols/subscribe* node1 "topic123" (fn [{:keys [dataBA from topics]}]
                                                (->
                                                 dataBA
                                                 (bytes.runtime.core/to-string)
                                                 (->> (println :received-msg)))))

  (-> @node3 :gossip-protocol (.getPeerTopics) (.get))

  (-> @node2 :gossip-router (.getScore) (.getPeerParams) (clojure.reflect/reflect) (clojure.pprint/pprint))
  (-> @node2 :gossip-router (.getScore) (.getPeerParams) (.isDirect) (clojure.reflect/reflect) (clojure.pprint/pprint))
  (-> @node2 :gossip-router (.getScore) (.getPeerParams) (.isDirect))
  (-> Function1 (clojure.reflect/reflect) (clojure.pprint/pprint))
  (->> @node2 :gossip-router (.getPeers) (map (fn [^P2PService$PeerHandler peer-handler]
                                                (-> ^GossipRouter (:gossip-router @node2) (.getScore) (.getPeerParams) (.isDirect) (.invoke (.getPeerId peer-handler))))))

  (-> (ipfs.protocols/publish* node2 "topic123" "message-from-node2") (.get))


  (do
    (ipfs.protocols/release* node1)
    (ipfs.protocols/release* node2)
    (ipfs.protocols/release* node3))


  ;
  )