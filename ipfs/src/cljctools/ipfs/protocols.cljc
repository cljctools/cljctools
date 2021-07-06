(ns cljctools.ipfs.protocols)

(defprotocol Release
  (release* [_]))

(defprotocol Connect
  (connect* [_] [_ a] [_ a b]))

(defprotocol Disconnect
  (disconnect* [_] [_ a] [_ a b]))

(defprotocol Send
  (send* [_ msg] [_ multiaddr msg]))

(defprotocol Dht
  (get-peer-id* [_])
  (get-listen-multiaddrs* [_])
  (ping* [_ multiaddr])
  (find-node* [_ multiaddr])
  #_Release
  #_IDeref)