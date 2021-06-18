(ns cljctools.ipfs.protocols)

(defprotocol ToByteArray
  (to-byte-array* [_]))

(defprotocol ToString
  (to-string* [_]))

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

(defprotocol Close
  (close* [_]))

(defprotocol Connection
  (connect* [_])
  (send* [_ msg])
  #_Close)