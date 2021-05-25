(ns cljctools.fs.protocols)

(defprotocol PWriter
  (write* [_ string])
  (close* [_]))