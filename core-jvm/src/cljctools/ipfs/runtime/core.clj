(ns cljctools.ipfs.runtime.core
  (:require
   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto])
  (:import
   (io.ipfs.multiaddr MultiAddress)
   (io.ipfs.multibase Multibase Base58)
   (io.ipfs.multihash Multihash Multihash$Type)
   (io.ipfs.cid Cid)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn to-base58
  [^bytes byte-arr]
  (io.ipfs.multibase.Base58/encode byte-arr))

(defn from-base58 ^bytes
  [^String string]
  (io.ipfs.multibase.Base58/decode string))

(defn encode-multihash
  [^bytes byte-arr]
  (if (<= (alength byte-arr) 42)
    (.toBytes (Multihash. Multihash$Type/id byte-arr))
    (.toBytes (Multihash. Multihash$Type/sha2_256 (ipfs.runtime.crypto/sha2-256 byte-arr)))))


(comment

  (require
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.ipfs.dht.core :as ipfs.dht.core]
   '[cljctools.ipfs.runtime.core :as ipfs.runtime.core]
   :reload)



  ;
  )
