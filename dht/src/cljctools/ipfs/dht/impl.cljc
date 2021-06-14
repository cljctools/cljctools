(ns cljctools.ipfs.dht.impl
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.varint.core :as varint.core]
   [cljctools.protobuf.core :as protobuf.core]
   #?@(:clj
       [[clojure.java.io :as io :refer [input-stream]]]))
  #?(:clj
     (:import (java.security SecureRandom Security MessageDigest)
              (org.bouncycastle.crypto.generators Ed25519KeyPairGenerator)
              (org.bouncycastle.crypto.params Ed25519KeyGenerationParameters Ed25519PrivateKeyParameters Ed25519PublicKeyParameters)
              (org.bouncycastle.crypto.signers Ed25519Signer)
              (org.bouncycastle.util.encoders Base64)
              (org.bouncycastle.jce.provider BouncyCastleProvider)
              (org.bouncycastle.crypto.digests SHA3Digest))))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))


#?(:clj
   (do
     (defn digest-stream
       "calculate SHA3-256 digest for given streaming input.
        As input may be:  File, URI, URL, Socket, byte array,
        or filename as String  which  will be coerced to BufferedInputStream and auto closed after.
        return digest as byte array."
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
       "generate Ed25519 key pair.
        return {:private `Ed25519PrivateKeyParameters`
        :public `Ed25519PublicKeyParameters`}"
       []
       (let [random (SecureRandom.)
             kpg    (Ed25519KeyPairGenerator.)
             _ (.init kpg (Ed25519KeyGenerationParameters. random))
             key-pair (.generateKeyPair kpg)]
         {:private (cast Ed25519PrivateKeyParameters (.getPrivate key-pair))
          :public  (cast Ed25519PublicKeyParameters (.getPublic key-pair))}))

     (defn new-signer
       "return new instance of `Ed25519Signer` initialized by private key"
       [private-key]
       (let [signer (Ed25519Signer.)]
         (.init signer true private-key)
         signer))

     (defn sign
       "generate signature for msg byte array.
        return byte array with signature."
       [^Ed25519Signer signer msg-bytes]
       (.update signer msg-bytes 0 (alength ^bytes msg-bytes))
       (.generateSignature signer))

     (defn new-verifier
       "return new instance of `Ed25519Signer` initialized by public key."
       [public-key]
       (let [signer (Ed25519Signer.)]
         (.init signer false public-key)
         signer))

     (defn verify
       "verify signature for msg byte array.
        return true if valid signature and false if not."
       [^Ed25519Signer signer msg-bytes signature]
       (.update signer msg-bytes 0 (alength ^bytes msg-bytes))
       (.verifySignature signer signature))

     (comment

       (require
        '[cljctools.bytes.core :as bytes.core]
        '[cljctools.ipfs.dht.impl :refer [generate-keypair
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
         (def msg (bytes.core/to-byte-array "asdasdasd"))
         (def digest (digest-stream msg))
         (def signature (sign s digest))
         (verify v (digest-stream msg) signature))
       
       ;
       )
     
     ;
     ))

(defn multiaddress-to-data
  [multiaddress]
  {})

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
      :msgBB (bytes.core/buffer-wrap buffer (+ header-size msg-length-size) msg-length)})))

(defn encode-mplex
  [{:as data
    :keys [flag stream-id msgBB]}]
  (bytes.core/concat
   [(let [baos (bytes.core/byte-array-output-stream)]
      (varint.core/encode-varint (bit-or (bit-shift-left stream-id 3) flag) baos)
      (varint.core/encode-varint (bytes.core/capacity msgBB) baos)
      (-> baos (bytes.protocols/to-byte-array*) (bytes.core/buffer-wrap)))
    msgBB]))


(comment

  (require
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.ipfs.varint.core :as varint.core]
   '[cljctools.ipfs.dht.impl :as dht.impl]
   :reload)

  ;
  )

