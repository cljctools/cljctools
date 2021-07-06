(ns cljctools.socket.protocols)

(defprotocol Close
  (close* [_]))

(defprotocol Socket
  (connect* [_])
  (send* [_ data])
  #_Close
  #_IDeref)

(defprotocol SocketServer
  (listen* [_])
  #_Close
  #_IDeref)