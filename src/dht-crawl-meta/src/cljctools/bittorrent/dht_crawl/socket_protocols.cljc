(ns cljctools.bittorrent.dht-crawl.socket-protocols)

(defprotocol Socket
  (listen* [_])
  #_IDeref)