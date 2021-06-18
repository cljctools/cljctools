(ns cljctools.ipfs.spec
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.ipfs.protocols :as ipfs.protocols]))

(s/def ::peer-id #(and
                   (satisfies? ipfs.protocols/PeerId %)
                   (satisfies? ipfs.protocols/ToByteArray %)
                   (satisfies? ipfs.protocols/ToString %)))

(s/def ::cid #(and
               (satisfies? ipfs.protocols/Cid %)
               (satisfies? ipfs.protocols/ToByteArray %)
               (satisfies? ipfs.protocols/ToString %)))


(s/def ::RSA keyword?)
(s/def ::Ed25519 keyword?)
(s/def ::Secp256k1 keyword?)
(s/def ::ECDCA keyword?)


(s/def ::public-key #(and
                      (satisfies? ipfs.protocols/Key %)
                      (satisfies? ipfs.protocols/PublicKey %)))

(s/def ::private-key #(and
                       (satisfies? ipfs.protocols/Key %)
                       (satisfies? ipfs.protocols/PrivateKey %)))

(s/def ::connection #(and
                      (satisfies? ipfs.protocols/Connection %)))



