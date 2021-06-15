(ns cljctools.ipfs.core
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.impl :as bytes.impl]
   [cljctools.varint.core :as varint.core]
   [cljctools.protobuf.core :as protobuf.core]))

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
      :msgBB (bytes.impl/buffer-wrap buffer (+ header-size msg-length-size) msg-length)})))

(defn encode-mplex
  [{:as data
    :keys [flag stream-id msgBB]}]
  (bytes.impl/concat
   [(let [baos (bytes.impl/byte-array-output-stream)]
      (varint.core/encode-varint (bit-or (bit-shift-left stream-id 3) flag) baos)
      (varint.core/encode-varint (bytes.impl/capacity msgBB) baos)
      (-> baos (bytes.protocols/to-byte-array*) (bytes.impl/buffer-wrap)))
    msgBB]))


(comment

  (require
   '[cljctools.bytes.impl :as bytes.impl]
   '[cljctools.ipfs.varint.core :as varint.core]
   '[cljctools.ipfs.impl :as ipfs.impl]
   '[cljctools.ipfs.core :as ipfs.core]
   :reload)

  ;
  )

