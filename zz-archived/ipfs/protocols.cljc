(ns cljctools.ipfs.protocols)

(defprotocol Node
  (get-peer-id* [_])
  (get-listen-multiaddrs* [_])
  (subscribe* [_ topic on-message])
  (unsubscribe* [_ topic])
  (publish* [_ topic msg])
  (ping* [_ multiaddr])
  (send-dht* [_ multiaddr msg])
  (find-node* [_ multiaddr])
  #_Release
  #_IDeref)

