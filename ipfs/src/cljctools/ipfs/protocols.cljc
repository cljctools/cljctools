(ns cljctools.ipfs.protocols)

(defprotocol Send
  (send* [_ msg] [_ multiaddr msg]))