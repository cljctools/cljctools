(ns cljctools.ipfs.crypto
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::RSA keyword?)
(s/def ::Ed25519 keyword?)
(s/def ::Secp256k1 keyword?)
(s/def ::ECDCA keyword?)

(defprotocol Key
  (key-type* [_])
  (to-protobuf-byte-array* [_])
  (to-byte-array* [_])
  (hash-code* [_]))

(defprotocol PrivateKey
  (sign* [_ data-byte-arr])
  (public-key* [_])
  #_Key)

(defprotocol PublicKey
  (verify* [_ dataBA signatureBA])
  #_Key)

(s/def ::public-key #(and
                      (satisfies? Key %)
                      (satisfies? PublicKey %)))

(s/def ::private-key #(and
                       (satisfies? Key %)
                       (satisfies? PrivateKey %)))