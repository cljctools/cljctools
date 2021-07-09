(ns cljctools.varint.core
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.runtime.core :as bytes.runtime.core]))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(defn varint-size
  [x]
  (loop [i (int 0)
         x (long x)]
    (if (zero? x)
      i
      (recur (inc i) (unsigned-bit-shift-right x 7)))))

(defn encode-varint
  [x baos]
  (loop [x (long x)]
    (if (zero? (bit-and x (bit-not 0x7f)))
      (do
        (bytes.protocols/write* baos x))
      (do
        (bytes.protocols/write* baos (-> (bytes.runtime.core/unchecked-int x) (bit-and 0x7f) (bit-or 0x80)))
        (recur (unsigned-bit-shift-right x 7))))))

(defn decode-varint
  [buffer]
  (loop [x (long 0)
         byte (bytes.runtime.core/get-byte buffer)
         shift (int 0)]
    (if (zero? (bit-and byte 0x80))
      (bit-or x (long (bit-shift-left (bit-and byte 0x7f) shift)))
      (recur (bit-or x (long (bit-shift-left (bit-and byte 0x7f) shift)))
             (bytes.runtime.core/get-byte buffer)
             (+ shift 7)))))

(defn encode-uint64
  [x baos]
  (encode-varint x baos))

(defn decode-uint64
  [buffer]
  (decode-varint buffer))

(defn encode-int64
  [x baos]
  (encode-varint x baos))

(defn decode-int64
  [buffer]
  (decode-varint buffer))

(defn encode-uint32
  [x baos]
  (encode-varint (long x) baos))

(defn decode-uint32
  [buffer]
  (decode-varint buffer))

(defn encode-int32
  [x baos]
  (encode-varint (long x) baos))

(defn decode-int32
  [buffer]
  (decode-varint buffer))

(defn encode-zig-zag64
  [x]
  (bit-xor (bit-shift-left x 1) (bit-shift-right x 63)))

(defn decode-zig-zag64
  [x]
  (bit-xor (unsigned-bit-shift-right x 1) (- (bit-and x 1))))

(defn encode-zig-zag32
  [x]
  (bit-xor (bit-shift-left x 1) (bit-shift-right x 31)))

(defn decode-zig-zag32
  [x]
  (decode-zig-zag64 x))

(defn encode-sint64
  [x baos]
  (encode-varint (encode-zig-zag64 x) baos))

(defn decode-sint64
  [buffer]
  (decode-zig-zag64 (decode-varint buffer)))

(defn encode-sint32
  [x baos]
  (encode-varint (encode-zig-zag32 x) baos))

(defn decode-sint32
  [buffer]
  (decode-zig-zag32 (decode-varint buffer)))



(comment

  (require
   '[cljctools.bytes.protocols :as bytes.protocols]
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.varint.core :as varint.core]
   :reload)

  [(let [baos (bytes.runtime.core/byte-array-output-stream)]
     (varint.core/encode-varint 1000 baos)
     (varint.core/decode-varint (-> baos (bytes.protocols/to-byte-array*) (bytes.runtime.core/buffer-wrap)) 0))

   (let [baos (bytes.runtime.core/byte-array-output-stream)]
     (varint.core/encode-varint 10000 baos)
     (varint.core/decode-varint (-> baos (bytes.protocols/to-byte-array*) (bytes.runtime.core/buffer-wrap)) 0))
   (let [baos (bytes.runtime.core/byte-array-output-stream)]
     (varint.core/encode-varint -1000000 baos)
     (varint.core/decode-varint (-> baos (bytes.protocols/to-byte-array*) (bytes.runtime.core/buffer-wrap)) 0))
   (let [baos (bytes.runtime.core/byte-array-output-stream)]
     (varint.core/encode-varint 100000000 baos)
     (varint.core/decode-varint (-> baos (bytes.protocols/to-byte-array*) (bytes.runtime.core/buffer-wrap)) 0))]


  [(let [baos (bytes.runtime.core/byte-array-output-stream)]
     (varint.core/encode-sint64 -10000 baos)
     (varint.core/decode-sint64 (-> baos (bytes.protocols/to-byte-array*) (bytes.runtime.core/buffer-wrap)) 0))]



  ;
  )



(comment

  (do
    (defn foo1
      [x]
      (bit-xor x x))

    (time
     (dotimes [i 10000000]
       (foo1 i)))
    ; "Elapsed time: 158.5587 msecs"


    (defn foo2
      [^long x]
      (bit-xor x x))

    (time
     (dotimes [i 10000000]
       (foo2 i)))
    ; "Elapsed time: 10.15018 msecs"


    (defn foo3
      [x]
      (bit-xor (int x) (int x)))

    (time
     (dotimes [i 10000000]
       (foo3 i)))
    ; "Elapsed time: 166.91291 msecs"


    (defn foo4
      [x]
      (bit-xor ^int x ^int x))

    (time
     (dotimes [i 10000000]
       (foo4 i)))
    ; "Elapsed time: 12.550342 msecs"

    ;
    )


  ;
  )