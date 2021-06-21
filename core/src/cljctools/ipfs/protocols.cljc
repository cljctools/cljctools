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

(defprotocol Release
  (release* [_]))

(defprotocol Connect
  (connect* [_]))

(defprotocol Send
  (send* [_ msg]))

(defprotocol Start
  (start* [_]))

(defprotocol Stop
  (stop* [_]))

(defprotocol Connection
  #_Connect
  #_Send
  #_Release)

(defprotocol Host
  #_Start
  #_Stop
  #_Connect)

