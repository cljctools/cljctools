(ns cljctools.datagram-socket.protocols)

(defprotocol Socket
  (close* [_])
  (listen* [_])
  (send* [_ data address])
  #_IDeref)