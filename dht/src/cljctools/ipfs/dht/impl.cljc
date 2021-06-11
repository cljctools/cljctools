(ns cljctools.ipfs.dht.impl
  (:require
   [cljctools.bytes.core :as bytes.core]
   [cljctools.ipfs.proto.core :as proto.core]))

(defn multiaddress-to-data
  [multiaddress]
  {})

(defn uvarint-size
  [value]
  (loop [i (int 0)
         x (long value)]
    (if (zero? x)
      i
      (recur (inc i) (unsigned-bit-shift-right x 7)))))

(defn encode-uvarint
  ([value]
   (encode-uvarint value (bytes.core/byte-buffer (uvarint-size value)) 0))
  ([value buffer offset]
   (loop [offset (int offset)
          x (long value)]
     (if (>= x 0x80)
       (do
         (bytes.core/put-uint8 buffer offset (-> (bit-and x 0x7f) (bit-or 0x80)))
         (recur (inc offset) (unsigned-bit-shift-right x 7)))
       (do
         (bytes.core/put-uint8 buffer offset (bit-and x 0x7f))
         buffer)))))

(defn decode-uvarint
  ([buffer]
   (decode-uvarint buffer 0))
  ([buffer offset]
   (loop [x (long 0)
          offset (int offset)
          byte (int (bytes.core/get-uint8 buffer offset))
          shift (int 0)]
     (if (< byte 0x80)
       (bit-or x (bit-shift-left byte shift))
       (recur (bit-or x (bit-shift-left (bit-and byte 0x7f) shift))
              (inc offset)
              (bytes.core/get-uint8 buffer (inc offset))
              (+ shift 7))))))


(defn decode-mplex
  ([buffer]
   (decode-mplex buffer 0))
  ([buffer offset]
   (let [header (decode-uvarint buffer 0)
         flag (bit-and header 0x07)
         stream-id (bit-shift-right header 3)
         header-size (uvarint-size header)
         msg-length (decode-uvarint buffer header-size)
         msg-length-size (uvarint-size msg-length)]
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
   [(encode-uvarint (bit-or (bit-shift-left stream-id 3) flag))
    (encode-uvarint (bytes.core/size msgBB))
    msgBB]))

(comment

  (require
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.ipfs.dht.impl :as dht.impl]
   :reload)

  [(->
    1000
    (dht.impl/encode-uvarint)
    (dht.impl/decode-uvarint))
   (->
    10000
    (dht.impl/encode-uvarint)
    (dht.impl/decode-uvarint))
   (->
    1000000
    (dht.impl/encode-uvarint)
    (dht.impl/decode-uvarint))
   (->
    100000000
    (dht.impl/encode-uvarint)
    (dht.impl/decode-uvarint))]

  ;
  )

