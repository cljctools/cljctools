(ns cljctools.ipfs.protocols)

(defprotocol ToByteArray
  (to-byte-array* [_]))

(defprotocol ToString
  (to-string* [_]))

(defprotocol MultiAddress
  #_ToByteArray
  #_ToString)

(defprotocol PeerId
  #_ToByteArray
  #_ToString)

(defprotocol Cid
  #_ToByteArray
  #_ToString)

(defprotocol Key
  (key-type* [_])
  (to-protobuf-byte-array* [_])
  (hash-code* [_])
  #_ToByteArray)

(defprotocol PrivateKey
  (sign* [_ data-byte-arr])
  (public-key* [_])
  #_Key)

(defprotocol PublicKey
  (verify* [_ dataBA signatureBA])
  #_Key)

(defprotocol Connection
  #_Connect
  #_Send
  #_Release)

(defprotocol Host
  #_Start
  #_Stop
  #_Connect)

(defprotocol Start
  (start* [_]))

(defprotocol Stop
  (stop* [_]))

(defprotocol Node
  (get-peer-id* [_])
  (get-listen-multiaddrs* [_])
  (subscribe* [_ topic on-message])
  (unsubscribe* [_ topic])
  (publish* [_ topic msg])
  (ping* [_ multiaddr])
  (send-dht* [_ multiaddr msg])
  (find-node* [_ multiaddr])
  #_Release
  #_IDeref)


(defprotocol Release
  (release* [_]))

(defprotocol Connect
  (connect* [_] [_ a] [_ a b]))

(defprotocol Disconnect
  (disconnect* [_] [_ a] [_ a b]))

(defprotocol Send
  (send* [_ msg] [_ multiaddr msg]))

(defprotocol Dht
  (get-peer-id* [_])
  (get-listen-multiaddrs* [_])
  (ping* [_ multiaddr])
  (find-node* [_ multiaddr])
  #_Release
  #_IDeref)