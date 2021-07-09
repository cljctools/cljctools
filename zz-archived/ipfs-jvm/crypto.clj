(ns cljctools.ipfs.runtime.crypto
  (:require
   [cljctools.ipfs.protocols :as ipfs.protocols]
   [cljctools.ipfs.spec :as ipfs.spec]
   [clojure.java.io :as io :refer [input-stream]])
  (:import
   (java.security PrivateKey PublicKey SecureRandom Security MessageDigest Signature KeyPairGenerator KeyPair KeyFactory Provider)
   (java.security.spec X509EncodedKeySpec PKCS8EncodedKeySpec RSAPublicKeySpec)
   (org.bouncycastle.crypto Signer KeyGenerationParameters)
   (org.bouncycastle.crypto.generators Ed25519KeyPairGenerator RSAKeyPairGenerator)
   (org.bouncycastle.crypto.params  Ed25519KeyGenerationParameters Ed25519PrivateKeyParameters Ed25519PublicKeyParameters RSAKeyGenerationParameters RSAKeyParameters RSAPrivateCrtKeyParameters)
   (org.bouncycastle.crypto.signers Ed25519Signer RSADigestSigner)
   (org.bouncycastle.util.encoders Base64)
   (org.bouncycastle.crypto.util PrivateKeyInfoFactory)
   (org.bouncycastle.jce.provider BouncyCastleProvider)
   (org.bouncycastle.crypto.digests SHA3Digest)
   (org.bouncycastle.asn1.pkcs RSAPrivateKey PrivateKeyInfo)
   (org.bouncycastle.asn1 ASN1Primitive)
   (com.google.protobuf ByteString)
   (org.bouncycastle.jcajce.provider.digest SHA3 SHA3$Digest224 SHA3$Digest256 SHA3$Digest384 SHA3$Digest512)
   (cljctools.ipfs.runtime DhtProto DhtProto$KeyType DhtProto$PrivateKey DhtProto$PublicKey)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(def ^Provider provider (BouncyCastleProvider.))

(defn sha1 [^bytes byte-arr] ^bytes
  (-> (java.security.MessageDigest/getInstance "SHA-1") (.digest byte-arr)))

(defn sha2-256 [^bytes byte-arr] ^bytes
  (-> (java.security.MessageDigest/getInstance "SHA-256") (.digest byte-arr)))

(defn sha2-512 [^bytes byte-arr] ^bytes
  (-> (java.security.MessageDigest/getInstance "SHA-512") (.digest byte-arr)))

(defn md5 [^bytes byte-arr] ^bytes
  (-> (java.security.MessageDigest/getInstance "md5") (.digest byte-arr)))

(defn sha3-224 [^bytes byte-arr] ^bytes
  (-> (SHA3$Digest224.) (.digest byte-arr)))

(defn sha3-256 [^bytes byte-arr] ^bytes
  (-> (SHA3$Digest256.) (.digest byte-arr)))

(defn sha3-384 [^bytes byte-arr] ^bytes
  (-> (SHA3$Digest384.) (.digest byte-arr)))

(defn sha3-512 [^bytes byte-arr] ^bytes
  (-> (SHA3$Digest512.) (.digest byte-arr)))

(defmulti create-public-key (fn [key-type & more] key-type))
(defmulti create-private-key (fn [key-type & more] key-type))
(defmulti generate-keypair (fn [key-type & more] key-type))
(defmulti decode-public-key (fn [key-type byte-arr] key-type))
(defmulti decode-private-key (fn [key-type byte-arr] key-type))

(defmethod create-public-key ::ipfs.spec/RSA
  [key-type ^PublicKey pub-key]
  (let [signature (Signature/getInstance "SHA256withRSA" provider)]
    (.initVerify signature pub-key)
    ^{:type ::ipfs.spec/public-key}
    (reify
      ipfs.protocols/Key
      (key-type* [_] key-type)
      (hash-code*
        [_]
        (.hashCode pub-key))
      ipfs.protocols/ToByteArray
      (to-byte-array*
        [_]
        (.getEncoded pub-key))
      ipfs.protocols/PublicKey
      (verify*
        [_ dataBA signatureBA]
        (.update signature ^bytes dataBA)
        (.verify signature ^bytes signatureBA)))))

(defmethod decode-public-key ::ipfs.spec/RSA
  [key-type byte-arr]
  (create-public-key key-type (->
                               (KeyFactory/getInstance "RSA" provider)
                               (.generatePublic (X509EncodedKeySpec. ^bytes byte-arr)))))

(defmethod create-private-key ::ipfs.spec/RSA
  [key-type ^PrivateKey priv-key ^PublicKey pub-key]
  (when-not (= (.getFormat priv-key) "PKCS#8")
    (throw (ex-info "RSA private key must be PKCS#8" {})))
  (let [signer (Ed25519Signer.)
        public-key (create-public-key ::ipfs.spec/RSA pub-key)
        pkcs1-priv-key-byte-arr (->
                                 (PrivateKeyInfo/getInstance (.getEncoded priv-key))
                                 (.parsePrivateKey)
                                 (.toASN1Primitive)
                                 (.getEncoded))
        signature (Signature/getInstance "SHA256withRSA" provider)]
    (.initSign signature priv-key)
    ^{:type ::ipfs.spec/private-key}
    (reify
      ipfs.protocols/Key
      (key-type* [_] key-type)
      (hash-code*
        [_]
        (.hashCode priv-key))
      ipfs.protocols/ToByteArray
      (to-byte-array*
        [_]
        pkcs1-priv-key-byte-arr)
      ipfs.protocols/PrivateKey
      (public-key*
        [_]
        public-key)
      (sign*
        [_ dataBA]
        (.update signature ^bytes dataBA)
        (.sign signature)))))

(defmethod decode-private-key ::ipfs.spec/RSA
  [key-type byte-arr]
  (let [rsa-priv-key (RSAPrivateKey/getInstance
                      (ASN1Primitive/fromByteArray ^bytes byte-arr))
        rsa-priv-key-params (RSAPrivateCrtKeyParameters.
                             (.getModulus rsa-priv-key)
                             (.getPublicExponent rsa-priv-key)
                             (.getPrivateExponent rsa-priv-key)
                             (.getPrime1 rsa-priv-key)
                             (.getPrime2 rsa-priv-key)
                             (.getExponent1 rsa-priv-key)
                             (.getExponent2 rsa-priv-key)
                             (.getCoefficient rsa-priv-key))
        rsa-priv-key-info (PrivateKeyInfoFactory/createPrivateKeyInfo rsa-priv-key-params)
        algorithm-id (-> rsa-priv-key-info
                         (.getPrivateKeyAlgorithm)
                         (.getAlgorithm)
                         (.getId))
        priv-key-spec (PKCS8EncodedKeySpec. (.getEncoded rsa-priv-key-info))
        priv-key (->
                  (KeyFactory/getInstance algorithm-id provider)
                  (.generatePrivate priv-key-spec))
        pub-key-spec (RSAPublicKeySpec.
                      (.getModulus rsa-priv-key-params)
                      (.getPublicExponent rsa-priv-key-params))
        pub-key (->
                 (KeyFactory/getInstance "RSA" provider)
                 (.generatePublic pub-key-spec))]
    (create-private-key key-type priv-key pub-key)))

(defmethod generate-keypair ::ipfs.spec/RSA
  [key-type bits]
  (let [kpg (doto
             (KeyPairGenerator/getInstance "RSA" provider)
              (.initialize ^int bits (SecureRandom.)))
        key-pair (.genKeyPair kpg)]
    {::ipfs.spec/private-key (create-private-key key-type (cast PrivateKey (.getPrivate key-pair)) (cast PublicKey (.getPublic key-pair)))
     ::ipfs.spec/public-key  (create-public-key key-type (cast PublicKey (.getPublic key-pair)))}))



(defmethod create-public-key ::ipfs.spec/Ed25519
  [key-type ^Ed25519PublicKeyParameters pub-key]
  (let [signer (Ed25519Signer.)]
    (.init signer false pub-key)
    ^{:type ::ipfs.spec/public-key}
    (reify
      ipfs.protocols/Key
      (key-type* [_] key-type)
      (hash-code*
        [_]
        (.hashCode pub-key))
      ipfs.protocols/ToByteArray
      (to-byte-array*
        [_]
        (.getEncoded pub-key))
      ipfs.protocols/PublicKey
      (verify*
        [_ dataBA signatureBA]
        (.update signer ^bytes dataBA 0 (alength ^bytes dataBA))
        (.verifySignature signer ^bytes signatureBA)))))

(defmethod decode-public-key ::ipfs.spec/Ed25519
  [key-type byte-arr]
  (create-public-key key-type (Ed25519PublicKeyParameters. ^bytes byte-arr 0)))

(defmethod create-private-key ::ipfs.spec/Ed25519
  [key-type ^Ed25519PrivateKeyParameters priv-key]
  (let [signer (Ed25519Signer.)]
    (.init signer true priv-key)
    ^{:type ::ipfs.spec/private-key}
    (reify
      ipfs.protocols/Key
      (key-type* [_] key-type)
      (hash-code*
        [_]
        (.hashCode priv-key))
      ipfs.protocols/ToByteArray
      (to-byte-array*
        [_]
        (.getEncoded priv-key))
      ipfs.protocols/PrivateKey
      (public-key* [_]
        (create-public-key ::ipfs.spec/Ed25519 (.generatePublicKey priv-key)))
      (sign*
        [_ dataBA]
        (.update signer dataBA 0 (alength ^bytes dataBA))
        (.generateSignature signer)))))

(defmethod decode-private-key ::ipfs.spec/Ed25519
  [key-type byte-arr]
  (create-private-key key-type (Ed25519PrivateKeyParameters. ^bytes byte-arr 0)))

(defmethod generate-keypair ::ipfs.spec/Ed25519
  [key-type]
  (let [kpg (doto
             (Ed25519KeyPairGenerator.)
              (.init (Ed25519KeyGenerationParameters. (SecureRandom.))))
        key-pair (.generateKeyPair kpg)]
    {::ipfs.spec/private-key (create-private-key key-type (cast Ed25519PrivateKeyParameters (.getPrivate key-pair)))
     ::ipfs.spec/public-key  (create-public-key key-type (cast Ed25519PublicKeyParameters (.getPublic key-pair)))}))

(def key-type-to-proto-enum
  {::ipfs.spec/RSA DhtProto$KeyType/RSA
   ::ipfs.spec/Ed25519 DhtProto$KeyType/Ed25519
   ::ipfs.spec/Secp256k1 DhtProto$KeyType/Secp256k1
   ::ipfs.spec/ECDSA DhtProto$KeyType/ECDSA})

(def proto-enum-to-key-type
  (->>
   key-type-to-proto-enum
   (map #(vector (second %) (first %)))
   (into {})))

(defn protobuf-encode-public-key
  [public-key]
  (->
   (DhtProto$PublicKey/newBuilder)
   (.setType ^DhtProto$KeyType (get key-type-to-proto-enum (ipfs.protocols/key-type* public-key)))
   (.setData (ByteString/copyFrom ^bytes (ipfs.protocols/to-byte-array* public-key)))
   (.build)
   (.toByteArray)))

(defn protobuf-decode-public-key
  [byte-arr]
  (let [pub-key-proto (DhtProto$PublicKey/parseFrom ^bytes byte-arr)
        pub-keyBA (-> pub-key-proto (.getData) (.toByteArray))]
    (decode-public-key (get proto-enum-to-key-type (.getType pub-key-proto)) pub-keyBA)))

(defn protobuf-encode-private-key
  [private-key]
  (->
   (DhtProto$PrivateKey/newBuilder)
   (.setType ^DhtProto$KeyType (get key-type-to-proto-enum (ipfs.protocols/key-type* private-key)))
   (.setData (ByteString/copyFrom ^bytes (ipfs.protocols/to-byte-array* private-key)))
   (.build)
   (.toByteArray)))

(defn protobuf-decode-private-key
  [byte-arr]
  (let [priv-key-proto (DhtProto$PrivateKey/parseFrom ^bytes byte-arr)
        priv-keyBA (-> priv-key-proto (.getData) (.toByteArray))]
    (decode-private-key (get proto-enum-to-key-type (.getType priv-key-proto)) priv-keyBA)))

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

(comment

  (require
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.ipfs.protocols :as ipfs.protocols]
   '[cljctools.ipfs.spec :as ipfs.spec]
   '[cljctools.ipfs.runtime.crypto :refer [generate-keypair
                                           protobuf-encode-private-key
                                           protobuf-decode-private-key
                                           protobuf-encode-public-key
                                           protobuf-decode-public-key]]
   :reload)

  (let [key-pair (generate-keypair ::ipfs.spec/Ed25519)
        msgBA (bytes.runtime.core/to-byte-array "asdasdasd")]
    (->>
     (ipfs.protocols/sign* (::ipfs.spec/private-key key-pair) msgBA)
     (ipfs.protocols/verify* (::ipfs.spec/public-key key-pair) msgBA)))

  (let [key-pair (generate-keypair ::ipfs.spec/RSA 2048)
        msgBA (bytes.runtime.core/to-byte-array "asdasdasd")]
    (->>
     (ipfs.protocols/sign* (::ipfs.spec/private-key key-pair) msgBA)
     (ipfs.protocols/verify* (::ipfs.spec/public-key key-pair) msgBA)))

  (let [key-pair
        #_(generate-keypair ::ipfs.spec/RSA 2048)
        (generate-keypair ::ipfs.spec/Ed25519)
        msgBA (bytes.runtime.core/to-byte-array "asdasdasd")
        private-key (->
                     (::ipfs.spec/private-key key-pair)
                     (protobuf-encode-private-key)
                     (protobuf-decode-private-key))
        public-key (->
                    (::ipfs.spec/public-key key-pair)
                    (protobuf-encode-public-key)
                    (protobuf-decode-public-key))]
    (->>
     (ipfs.protocols/sign* private-key msgBA)
     (ipfs.protocols/verify* public-key msgBA)))


  ;
  )
