(ns cljctools.runtime.bytes.protocols)

(defprotocol IPushbackInputStream
  (read* [_] [_ offset length])
  (unread* [_ char-int]))

(defprotocol IOutputStream
  (write* [_ data])
  (to-bytes* [_])
  (reset* [_]))

(defprotocol Closable
  (close [_]))
