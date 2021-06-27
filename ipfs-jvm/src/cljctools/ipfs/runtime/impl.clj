(ns cljctools.ipfs.runtime.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! do-alts alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]

   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.varint.core :as varint.core]

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
   (io.libp2p.core.multiformats Multiaddr MultiaddrDns Protocol)
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
   (cljctools.ipfs.runtime NodeProto$DhtMessage NodeProto$DhtMessage$Type)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defmulti to-byte-array type)

(defmethod to-byte-array ByteBuf ^bytes
  [^ByteBuf bytebuf]
  (let [byte-arr (byte-array (.readableBytes bytebuf))]
    (->   (.slice bytebuf)
          (.readBytes byte-arr))
    byte-arr))

(def dht-max-request-size (* 1024 1024))
(def dht-max-response-size (* 1024 1024))

(defprotocol DhtController
  (send* [_ msg]))

(defn create-dht-protocol ^StrictProtocolBinding
  [{:as opts
    :keys [on-message]}]
  (let [protocol
        (proxy
         [ProtobufProtocolHandler]
         [(NodeProto$DhtMessage/getDefaultInstance) dht-max-request-size dht-max-response-size]
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
                              (on-message stream msg)
                              #_(let [msg ^NodeProto$DhtMessage msg]
                                  (println :requester-recv-dht-message (-> msg (.getType) (.name)))
                                  (when (= (.getType msg) NodeProto$DhtMessage$Type/FIND_NODE)
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
                             (on-message stream msg)
                             #_(println :responder-recv-dht-message (-> ^NodeProto$DhtMessage msg (.getType) (.name))))
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

(defn cfuture-to-channel
  ([^CompletableFuture cfuture]
   (cfuture-to-channel cfuture nil))
  ([^CompletableFuture cfuture timeout|]
   (let [result| (chan 1)
         out| (chan 1)]
     (.thenApply cfuture (reify Function
                           (apply [_ result]
                             (put! result| result))))
     (do-alts (fn [[value port]]
                (.cancel cfuture true)
                (if (some? value)
                  (put! out| value)
                  (close! out|))) (if timeout| [result| timeout|] [result|]) {})
     out|)))

(defn create-host ^Host
  [protocols]
  (->
   (HostBuilder.)
   (.protocol (into-array ProtocolBinding protocols))
   (.secureChannel
    (into-array Function [(reify Function
                            (apply
                              [_ priv-key]
                              (NoiseXXSecureChannel. ^PrivKey priv-key)))]))
   (.listen (into-array String ["/ip4/127.0.0.1/tcp/0"]))
   (.build)))

(defn connect
  ([host multiaddr]
   (connect host (.getFirst (.toPeerIdAndAddr ^Multiaddr multiaddr)) [(.getSecond (.toPeerIdAndAddr ^Multiaddr multiaddr))]))
  ([host peer-id multiaddrs]
   (->
    ^Host host
    (.getNetwork)
    (.connect ^PeerId peer-id (into-array Multiaddr multiaddrs))
    (.thenApply (reify Function
                  (apply [_ connection]
                    (println ::connected connection)))))))

(defn ping
  [ping-protocol host multiaddr]
  (let [^PingController pinger (-> ^Ping ping-protocol (.dial ^Host host ^Multiaddr multiaddr) (.getController) (.get 5 TimeUnit/SECONDS))]
    (dotimes [i 5]
      (let [latency (-> pinger (.ping) (.get 5 TimeUnit/SECONDS))]
        (println latency)))))

(defn ip4-tcp-multiaddr
  [multiaddrs]
  (->>
   multiaddrs
   (filter (fn [^Multiaddr multiaddr]
             (and (.has multiaddr Protocol/IP4)
                  (.has multiaddr Protocol/TCP))))
   (first)))