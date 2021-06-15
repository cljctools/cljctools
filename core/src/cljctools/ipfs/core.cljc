(ns cljctools.ipfs.core
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.impl.core :as bytes.impl.core]
   [cljctools.ipfs.impl.core :as ipfs.impl.core]))

(defn multiaddress-to-data
  [multiaddress]
  {})

(defn decode-mplex
  ([buffer]
   (decode-mplex buffer 0))
  ([buffer offset]
   (let [header (ipfs.impl.core/decode-varint buffer 0)
         flag (bit-and header 0x07)
         stream-id (bit-shift-right header 3)
         header-size (ipfs.impl.core/varint-size header)
         msg-length (ipfs.impl.core/decode-varint buffer header-size)
         msg-length-size (ipfs.impl.core/varint-size msg-length)]
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
      :msgBB (bytes.impl.core/buffer-wrap buffer (+ header-size msg-length-size) msg-length)})))

(defn encode-mplex
  [{:as data
    :keys [flag stream-id msgBB]}]
  (bytes.impl.core/concat
   [(let [baos (bytes.impl.core/byte-array-output-stream)]
      (ipfs.impl.core/encode-varint (bit-or (bit-shift-left stream-id 3) flag) baos)
      (ipfs.impl.core/encode-varint (bytes.impl.core/capacity msgBB) baos)
      (-> baos (bytes.protocols/to-byte-array*) (bytes.impl.core/buffer-wrap)))
    msgBB]))


(comment

  (require
   '[cljctools.bytes.impl.core :as bytes.impl.core]
   '[cljctools.ipfs.impl.core :as ipfs.impl.core]
   '[cljctools.ipfs.core :as ipfs.core]
   :reload)

  ;
  )

