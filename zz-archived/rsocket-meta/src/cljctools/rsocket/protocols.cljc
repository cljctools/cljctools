(ns cljctools.rsocket.protocols)

(defprotocol RSocket
  :extend-via-metadata true
  (-request-response [_ opts])
  (-fire-and-forget [_ opts])
  (-request-stream [_ opts])
  (-request-channel [_opts]))