(ns cljctools.ipfs.core
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.varint.core :as varint.core]
   [cljctools.ipfs.crypto :as ipfs.crypto]
   [cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto]
   [cljctools.ipfs.runtime.core :as ipfs.runtime.core]))


(defn create-peer-id
  [public-key]
  (->
   public-key
   (ipfs.runtime.crypto/protobuf-encode-public-key)
   (ipfs.runtime.core/encode-multihash)))


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
      :msgBB (bytes.runtime.core/buffer-wrap buffer (+ header-size msg-length-size) msg-length)})))

(defn encode-mplex
  [{:as data
    :keys [flag stream-id msgBB]}]
  (bytes.runtime.core/concat
   [(let [baos (bytes.runtime.core/byte-array-output-stream)]
      (varint.core/encode-varint (bit-or (bit-shift-left stream-id 3) flag) baos)
      (varint.core/encode-varint (bytes.runtime.core/capacity msgBB) baos)
      (-> baos (bytes.protocols/to-byte-array*) (bytes.runtime.core/buffer-wrap)))
    msgBB]))





(comment

  (require
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.varint.core :as varint.core]
   '[cljctools.ipfs.crypto :as ipfs.crypto]
   '[cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto]
   '[cljctools.ipfs.runtime.core :as ipfs.runtime.core]
   '[cljctools.ipfs.core :as ipfs.core]
   :reload)

  (do
    (def key-pair (ipfs.runtime.crypto/generate-keypair ::ipfs.crypto/RSA 2048))
    (def private-key (::ipfs.crypto/private-key key-pair))
    (def public-key (::ipfs.crypto/public-key key-pair))
    (def peer-idBA (ipfs.core/create-peer-id public-key))
    (ipfs.runtime.core/to-base58 peer-idBA))

  ;
  )

