(ns cljctools.ipfs.runtime.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]

   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.varint.core :as varint.core]

   [manifold.deferred :as d]
   [manifold.stream :as sm]
   [aleph.tcp]

   [cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto]
   [cljctools.ipfs.protocols :as ipfs.protocols]
   [cljctools.ipfs.spec :as ipfs.spec])
  (:import
   (io.ipfs.multiaddr MultiAddress)
   (io.ipfs.multibase Multibase Base58)
   (io.ipfs.multihash Multihash Multihash$Type)
   (io.ipfs.cid Cid Cid$Codec)
   (com.southernstorm.noise.protocol Noise CipherState DHState HandshakeState)
   (java.net InetAddress InetSocketAddress)
   (io.netty.bootstrap Bootstrap)
   (io.netty.channel ChannelPipeline)
   (io.libp2p.core Connection Host PeerId)
   (io.libp2p.core.dsl HostBuilder)
   (io.libp2p.core.multiformats Multiaddr MultiaddrDns)
   (io.libp2p.core Libp2pException Stream P2PChannelHandler)
   (io.libp2p.core.multistream  ProtocolBinding StrictProtocolBinding)
   (io.libp2p.protocol Ping PingController ProtocolHandler ProtobufProtocolHandler
                       ProtocolMessageHandler ProtocolMessageHandler$DefaultImpls)
   (io.libp2p.security.noise NoiseXXSecureChannel)
   (io.libp2p.core.crypto PrivKey)
   (io.libp2p.pubsub.gossip Gossip)
   (io.libp2p.core.pubsub Topic MessageApi)
   (java.util.function Function Consumer)
   (io.netty.buffer ByteBuf ByteBufUtil Unpooled)
   (java.util.concurrent CompletableFuture TimeUnit)
   (com.google.protobuf ByteString)
   (cljctools.ipfs.runtime DhtProto$DhtMessage DhtProto$DhtMessage$Type)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn to-base58
  [^bytes byte-arr]
  (io.ipfs.multibase.Base58/encode byte-arr))

(defn from-base58 ^bytes
  [^String string]
  (io.ipfs.multibase.Base58/decode string))

(defmulti create-cid (fn [x & more] (type x)))

(defmethod create-cid Cid
  [^Cid cid]
  (let [byte-arr (.toBytes cid)
        string (.toString cid)]
    ^{:type ::ipfs.spec/cid}
    (reify
      ipfs.protocols/Cid
      ipfs.protocols/ToByteArray
      (to-byte-array*
        [_]
        byte-arr)
      ipfs.protocols/ToString
      (to-string*
        [_]
        string))))

(defmethod create-cid bytes.runtime.core/ByteArray
  [byte-arr]
  (create-cid (Cid/cast ^bytes byte-arr)))

(defmethod create-cid String
  [string]
  (create-cid (Cid/decode ^String string)))

(defn encode-multihash
  [^bytes protobuf-byte-arr]
  (if (<= (alength protobuf-byte-arr) 42)
    (.toBytes (Multihash. Multihash$Type/id protobuf-byte-arr))
    (.toBytes (Multihash. Multihash$Type/sha2_256 (ipfs.runtime.crypto/sha2-256 protobuf-byte-arr)))))

(defn decode-multihash
  [^bytes byte-arr]
  (.getHash (Multihash/deserialize byte-arr)))

(defmulti create-peer-id (fn [x & more] (type x)))

(defmethod create-peer-id bytes.runtime.core/ByteArray
  ([^bytes byte-arr]
   (create-peer-id byte-arr (to-base58 byte-arr)))
  ([byte-arr string]
   (let []
     ^{:type ::ipfs.spec/peer-id}
     (reify
       ipfs.protocols/PeerId
       ipfs.protocols/ToByteArray
       (to-byte-array*
         [_]
         byte-arr)
       ipfs.protocols/ToString
       (to-string*
         [_]
         string)))))

(defmethod create-peer-id String
  [string]
  (if (or (clojure.string/starts-with? string "1")
          (clojure.string/starts-with? string "Qm"))
    (create-peer-id (from-base58 string))
    (let [cid (create-cid string)]
      (create-peer-id (ipfs.protocols/to-byte-array* cid) (ipfs.protocols/to-string* cid)))))

(defmethod create-peer-id ::ipfs.spec/public-key
  [public-key]
  (->
   public-key
   (ipfs.runtime.crypto/protobuf-encode-public-key)
   (encode-multihash)
   (create-peer-id)))

(defn create-connection
  []
  (let []
    ^{:type ::ipfs.spec/connection}
    (reify
      ipfs.protocols/Connection
      (connect*
       [t]
       )
      (send*
        [_ byte-arr]
        )
      ipfs.protocols/Close
      (close*
       [_]))))

(defmulti to-byte-array type)

(defmethod to-byte-array ByteBuf ^bytes
  [^ByteBuf bytebuf]
  (let [byte-arr (byte-array (.readableBytes bytebuf))]
    (->   (.slice bytebuf)
          (.readBytes byte-arr))
    byte-arr))


(comment

  (require
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.codec.runtime.core :as codec.runtime.core]
   '[cljctools.varint.core :as varint.core]
   '[cljctools.ipfs.protocols :as ipfs.protocols]
   '[cljctools.ipfs.spec :as ipfs.spec]
   '[cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto]
   '[cljctools.ipfs.runtime.core :as ipfs.runtime.core]
   '[cljctools.ipfs.core :as ipfs.core]
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

(def dht-max-request-size (* 1024 1024))
(def dht-max-response-size (* 1024 1024))

(defprotocol DhtController
  (send* [_ msg]))

(defn create-dht-protocol
  []
  (let [protocol
        (proxy
         [ProtobufProtocolHandler]
         [(DhtProto$DhtMessage/getDefaultInstance) dht-max-request-size dht-max-response-size]
          (onStartInitiator
            [stream]
            (println ::onStartInitiator)
            (let [handler (reify
                            ProtocolMessageHandler
                            (onActivated
                              [_ stream]
                              (println :dht-requester-activated))
                            (onMessage
                              [_ stream msg]
                              (let [msg ^DhtProto$DhtMessage msg]
                                (println :requester-recv-dht-message (-> msg (.getType) (.name)))
                                (when (= (.getType msg) DhtProto$DhtMessage$Type/FIND_NODE)
                                  (println (.size ^java.util.List (.getCloserPeersList msg))))))
                            (onClosed
                              [_ stream]
                              (println :dht-connection-closed))
                            (onException
                              [_ cause]
                              (println :dht-requester-exception ^Throwable cause))
                            (fireMessage
                              [t stream msg]
                              #_(.onMessage t stream msg)
                              (ProtocolMessageHandler$DefaultImpls/fireMessage t ^Stream stream msg))
                            DhtController
                            (send*
                              [_ msg]
                              (.writeAndFlush ^Stream stream msg)))]
              (.pushHandler ^Stream stream handler)
              (CompletableFuture/completedFuture handler)))
          (onStartResponder
            [stream]
            (println ::onStartResponder)
            (let [^Multiaddr remote-address (-> ^Stream stream (.getConnection) (.remoteAddress))
                  handler (reify
                            ProtocolMessageHandler
                            (onActivated
                              [_ stream]
                              (println :dht-responder-activated))
                            (onMessage
                              [_ stream msg]
                              (println :responder-recv-dht-message (-> ^DhtProto$DhtMessage msg (.getType) (.name))))
                            (onClosed
                              [_ stream]
                              (println :dht-responder-connection-closed))
                            (onException
                              [_ cause]
                              (println :dht-responder-exception ^Throwable cause))
                            (fireMessage
                              [t stream msg]
                              #_(.onMessage t stream msg)
                              (ProtocolMessageHandler$DefaultImpls/fireMessage t ^Stream stream msg)))]
              (.pushHandler ^Stream stream handler)
              (CompletableFuture/completedFuture handler))))]
    (proxy [StrictProtocolBinding] ["/ipfs/kad/1.0.0" protocol])))

(def dns-protocols (into-array [io.libp2p.core.multiformats.Protocol/DNS4
                                io.libp2p.core.multiformats.Protocol/DNS6
                                io.libp2p.core.multiformats.Protocol/DNSADDR]))

(comment

  (do
    (require
     '[cljctools.bytes.runtime.core :as bytes.runtime.core]
     '[cljctools.codec.runtime.core :as codec.runtime.core]
     '[cljctools.varint.core :as varint.core]
     '[cljctools.ipfs.protocols :as ipfs.protocols]
     '[cljctools.ipfs.spec :as ipfs.spec]
     '[cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto]
     '[cljctools.ipfs.runtime.core :as ipfs.runtime.core]
     '[cljctools.ipfs.core :as ipfs.core]
     :reload)
    (import
     '(io.libp2p.core Connection Host PeerId)
     '(io.libp2p.core.dsl HostBuilder)
     '(java.net InetAddress InetSocketAddress)
     '(io.libp2p.core.multiformats Multiaddr MultiaddrDns)
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
     '(io.libp2p.pubsub.gossip Gossip)
     '(io.libp2p.core.pubsub Topic MessageApi)
     '(io.libp2p.discovery MDnsDiscovery)
     '(com.google.protobuf ByteString)
     '(cljctools.ipfs.runtime DhtProto$DhtMessage DhtProto$DhtMessage$Type)))

  (do
    (def node (->
               (HostBuilder.)
               (.protocol (into-array ProtocolBinding [(Ping.) (ipfs.runtime.core/create-dht-protocol)]))
               (.secureChannel
                (into-array Function [(reify Function
                                        (apply
                                          [_ priv-key]
                                          (NoiseXXSecureChannel. ^PrivKey priv-key)))]))
               (.listen (into-array String ["/ip4/127.0.0.1/tcp/0"]))
               (.build)))
    (-> node (.start) (.get))
    (println (format "node listening on \n %s" (.listenAddresses node))))

  (do
    (def address (Multiaddr/fromString "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"))
    (def pinger (-> (Ping.) (.dial node address) (.getController) (.get 5 TimeUnit/SECONDS)))
    (dotimes [i 5]
      (let [latency (-> pinger (.ping) (.get 5 TimeUnit/SECONDS))]
        (println latency))))

  (.stop node)
  
  (def dht-controller (-> (ipfs.runtime.core/create-dht-protocol) (.dial node address) (.getController) (.get 5 TimeUnit/SECONDS)))

  (ipfs.runtime.core/send* dht-controller
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
                  (.protocol (into-array ProtocolBinding [(Ping.) gossip (ipfs.runtime.core/create-dht-protocol)]))
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
      ([host peerid multiaddrs]
       (->
        host
        (.getNetwork)
        (.connect ^PeerId peerid (into-array Multiaddr multiaddrs))
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
                                                         (ipfs.runtime.core/to-byte-array)
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

    (def host3 (start-host gossip2)))

  (.getPeerId host1)
  (.listenAddresses host1)


  (connect host1 (.getPeerId host2) (.listenAddresses host2))

  (subsribe gossip1  :gossip1  "topic1")
  (subsribe gossip2  :gossip2  "topic1")

  (def publisher1 (.createPublisher gossip1 (.getPrivKey host1) 1234))
  (def publisher2 (.createPublisher gossip2 (.getPrivKey host2) 4321))


  (publish publisher1 "topic1" "from publisher1")
  (publish publisher2 "topic1" "from publisher2")
  
  (connect host1 (.getPeerId host3) (.listenAddresses host3))
  (connect host2 (.getPeerId host3) (.listenAddresses host3))
  

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

  (defn decode-mplex
    ([buffer]
     (decode-mplex buffer 0))
    ([buffer offset]
     (let [header (varint.core/decode-varint buffer 0)
           flag (bit-and header 0x07)
           stream-id (bit-shift-right header 3)
           header-size (varint.core/varint-size header)
           msg-length (varint.core/decode-varint buffer header-size)
           msg-length-size (varint.core/varint-size msg-length)]
       {:flag (case flag
                0 :new-stream
                1 :message-receiver
                2 :message-initiator
                3 :close-receiver
                4 :close-initiator
                5 :reset-receiver
                6 :reset-initiator)
        :stream-id stream-id
        :msg-length msg-length
        :msgBB (bytes.runtime.core/buffer-slice buffer (+ header-size msg-length-size) msg-length)})))

  (defn encode-mplex
    [{:as data
      :keys [flag stream-id msgBB]}]
    (bytes.runtime.core/concat
     [(let [baos (bytes.runtime.core/byte-array-output-stream)]
        (varint.core/encode-varint (bit-or (bit-shift-left stream-id 3) flag) baos)
        (varint.core/encode-varint (bytes.runtime.core/capacity msgBB) baos)
        (-> baos (bytes.protocols/to-byte-array*) (bytes.runtime.core/buffer-wrap)))
      msgBB]))

  (def multistreamBA (bytes.runtime.core/to-byte-array "/multistream/1.0.0\n"))
  (def not-availableBA (bytes.runtime.core/to-byte-array "na\n"))
  (def newlineBA (bytes.runtime.core/to-byte-array "\n"))
  (def noiseBA (bytes.runtime.core/to-byte-array "/noise"))
  (def mplexBA (bytes.runtime.core/to-byte-array "/mplex/1.0.0"))

  ;
  )