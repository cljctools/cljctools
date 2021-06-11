(ns cljctools.varint.core
  (:require
   [cljctools.bytes.core :as bytes.core]))

(defn varint-size
  [value]
  (loop [i (int 0)
         x (long value)]
    (if (zero? x)
      i
      (recur (inc i) (unsigned-bit-shift-right x 7)))))

(defn encode-varint
  ([value]
   (encode-varint value (bytes.core/byte-buffer (varint-size value)) 0))
  ([value buffer offset]
   (loop [offset (int offset)
          x (long value)]
     (if (zero? (bit-and x (bit-not 0x7f)))
       (do
         (bytes.core/put-byte buffer offset (bytes.core/unchecked-byte x))
         buffer)
       (do
         (bytes.core/put-byte buffer offset (-> (bytes.core/unchecked-int x) (bit-and 0x7f) (bit-or 0x80) (bytes.core/unchecked-byte)))
         (recur (inc offset) (unsigned-bit-shift-right x 7)))))))

(defn decode-varint
  ([buffer]
   (decode-varint buffer 0))
  ([buffer offset]
   (loop [x (long 0)
          offset (int offset)
          byte (int (bytes.core/get-byte buffer offset))
          shift (int 0)]
     (if (zero? (bit-and byte 0x80))
       (bit-or x (long (bit-shift-left (bit-and byte 0x7f) shift)))
       (recur (bit-or x (long (bit-shift-left (bit-and byte 0x7f) shift)))
              (inc offset)
              (int (bytes.core/get-byte buffer (inc offset)))
              (+ shift 7))))))

(comment

  (require
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.varint.core :as varint.core]
   :reload)

  [(->
    1000
    (varint.core/encode-varint)
    (varint.core/decode-varint))
   (->
    10000
    (varint.core/encode-varint)
    (varint.core/decode-varint))
   (->
    1000000
    (varint.core/encode-varint)
    (varint.core/decode-varint))
   (->
    100000000
    (varint.core/encode-varint)
    (varint.core/decode-varint))]


  ;
  )