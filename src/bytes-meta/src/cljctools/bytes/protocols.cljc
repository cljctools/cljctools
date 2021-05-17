(ns cljctools.bytes.protocols)

(defprotocol IPushbackInputStream
  (read* [_] [_ offset length])
  (unread* [_ char-int]))

(defprotocol IOutputStream
  (write* [_ char-int])
  (write-bytes* [_ byts])
  (to-bytes* [_])
  (reset* [_]))

(defprotocol Closable
  (close [_]))
