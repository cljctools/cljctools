(ns cljctools.bytes.protocols)

(defprotocol IPushbackInputStream
  (read* [_] [_ offset length])
  (unread* [_ char-int]))

(defprotocol IToByteArray
  (to-byte-array* [_]))

(defprotocol IOutputStream
  (write* [_ char-int])
  (write-byte-array* [_ byte-arr])
  (reset* [_])
  #_IToByteArray)

(defprotocol Closable
  (close [_]))

(defprotocol IBitSet
  (get* [_ bit-index])
  (get-subset* [_ from-index to-index])
  (set* [_ bit-index] [_ bit-index value])
  #_IToByteArray)
