(ns cljctools.socket.protocols)

(defprotocol Socket
  (connect* [_] [_ opts])
  (disconnect* [_])
  (close* [_])
  (send* [_ data] "data is passed directly to ::send-fn"))