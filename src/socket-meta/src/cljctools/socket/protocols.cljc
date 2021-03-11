(ns cljctools.socket.protocols)

(defprotocol Socket
  (connect* [_])
  (disconnect* [_])
  (close* [_])
  (send* [_ data] "data is passed directly to ::send-fn"))