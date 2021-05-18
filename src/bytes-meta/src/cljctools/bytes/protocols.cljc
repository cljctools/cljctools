(ns cljctools.bytes.protocols)

(defprotocol IPushbackInputStream
  (read* [_] [_ offset length])
  (unread* [_ char-int]))

(defprotocol IToBytes
  (to-bytes* [_]))

(defprotocol IToArray
  (to-array* [_]))

(defprotocol IOutputStream
  (write* [_ char-int])
  (write-bytes* [_ byts])
  (reset* [_])
  #_IToBytes)

(defprotocol Closable
  (close [_]))

(defprotocol IBitSet
  (get* [_ bit-index])
  (get-subset* [_ from-index to-index])
  (set* [_ bit-index] [_ bit-index value])
  #_IToBytes
  #_IToArray)
