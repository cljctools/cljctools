(ns cljctools.ipfs.runtime.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! do-alts alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]

   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.runtime.core :as bytes.runtime.core]

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

#_(do
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
    (def mplexBA (bytes.runtime.core/to-byte-array "/mplex/1.0.0")))

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
      ipfs.protocols/Connect
      (connect*
        [t])
      ipfs.protocols/Send
      (send*
        [_ byte-arr])
      ipfs.protocols/Release
      (release*
        [_]))))

(def dns-protocols (into-array [io.libp2p.core.multiformats.Protocol/DNS4
                                io.libp2p.core.multiformats.Protocol/DNS6
                                io.libp2p.core.multiformats.Protocol/DNSADDR]))