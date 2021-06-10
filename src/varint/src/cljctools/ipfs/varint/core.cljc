(ns cljctools.ipfs.varint.core
  (:require
   [cljctools.bytes.core :as bytes.core]))

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

(comment

  (require
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.ipfs.varint.core :as varint.core]
   :reload)

  [(->
    1000
    (varint.core/encode-uvarint)
    (varint.core/decode-uvarint))
   (->
    10000
    (varint.core/encode-uvarint)
    (varint.core/decode-uvarint))
   (->
    1000000
    (varint.core/encode-uvarint)
    (varint.core/decode-uvarint))
   (->
    100000000
    (varint.core/encode-uvarint)
    (varint.core/decode-uvarint))]


  ;
  )