(ns cljctools.ipfs.runtime.core
  (:require
   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [clojure.java.io :as io :refer [input-stream]])
  (:import
   (java.security SecureRandom Security MessageDigest)
   (org.bouncycastle.crypto KeyGenerationParameters)
   (org.bouncycastle.crypto.generators Ed25519KeyPairGenerator RSAKeyPairGenerator)
   (org.bouncycastle.crypto.params  Ed25519KeyGenerationParameters Ed25519PrivateKeyParameters Ed25519PublicKeyParameters)
   (org.bouncycastle.crypto.signers Ed25519Signer RSADigestSigner)
   (org.bouncycastle.util.encoders Base64)
   (org.bouncycastle.jce.provider BouncyCastleProvider)
   (org.bouncycastle.crypto.digests SHA3Digest)
   (io.ipfs.multiaddr MultiAddress)
   (io.ipfs.multibase Multibase Base58)
   (io.ipfs.multihash Multihash)
   (io.ipfs.cid Cid)
   (cljctools.ipfs.runtime DhtProto)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn digest-stream
  [input]
  (Security/addProvider (BouncyCastleProvider.))
  (with-open [in (input-stream input)]
    (let [buf (byte-array 1024)
          digest (SHA3Digest.)
          hash-buffer (byte-array (.getDigestSize digest))]
      (loop [n (.read in buf)]
        (if (<= n 0)
          (do (.doFinal digest hash-buffer 0) hash-buffer)
          (recur (do (.update digest buf 0 n) (.read in buf))))))))

(defn generate-keypair
  []
  (let [random (SecureRandom.)
        kpg    (Ed25519KeyPairGenerator.)
        _ (.init kpg (Ed25519KeyGenerationParameters. random))
        key-pair (.generateKeyPair kpg)]
    {:private (cast Ed25519PrivateKeyParameters (.getPrivate key-pair))
     :public  (cast Ed25519PublicKeyParameters (.getPublic key-pair))}))

(defn new-signer
  [private-key]
  (let [signer (Ed25519Signer.)]
    (.init signer true private-key)
    signer))

(defn sign
  [^Ed25519Signer signer msg-bytes]
  (.update signer msg-bytes 0 (alength ^bytes msg-bytes))
  (.generateSignature signer))

(defn new-verifier
  [public-key]
  (let [signer (Ed25519Signer.)]
    (.init signer false public-key)
    signer))

(defn verify
  [^Ed25519Signer signer msg-bytes signature]
  (.update signer msg-bytes 0 (alength ^bytes msg-bytes))
  (.verifySignature signer signature))

(defn varint-size
  [buffer offset])

(defn encode-varint
  [x baos])

(defn decode-varint
  [buffer offset])


(comment

  (require
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.crypto.core :refer [generate-keypair
                                   new-signer
                                   new-verifier
                                   digest-stream
                                   sign
                                   verify]]
   :reload)

  (do
    (def kp (generate-keypair))
    (def s (new-signer (:private kp)))
    (def v (new-verifier (:public kp)))
    (def msg (bytes.runtime.core/to-byte-array "asdasdasd"))
    (def digest (digest-stream msg))
    (def signature (sign s digest))
    (verify v (digest-stream msg) signature))

  ;
  )
