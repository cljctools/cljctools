(ns cljctools.bytes.protocols)

(defprotocol IPushbackInputStream
  (read* [_] [_ length])
  (unread* [_ char-int])
  (unread-byte-array* [_ byte-arr] [_ byte-arr offset length]))

(defprotocol IToByteArray
  (to-byte-array* [_]))

(defprotocol IByteArrayOutputStream
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
