(ns cljctools.ipfs.dht.impl
  (:require
   [cljctools.bytes.core :as bytes.core]
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
      :msgBB (bytes.core/buffer-wrap buffer (+ header-size msg-length-size) msg-length)})))

(defn encode-mplex
  [{:as data
    :keys [flag stream-id msgBB]}]
  (bytes.core/concat
   [(varint.core/encode-varint (bit-or (bit-shift-left stream-id 3) flag))
    (varint.core/encode-varint (bytes.core/size msgBB))
    msgBB]))

(comment

  (require
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.ipfs.varint.core :as varint.core]
   '[cljctools.ipfs.dht.impl :as dht.impl]
   :reload)

  ;
  )

