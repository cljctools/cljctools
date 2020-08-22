(ns cljctools.net.core.protocols)

(defprotocol Connect
  (-connect [_])
  (-disconnect [_])
  (-connected? [_]))

(defprotocol Start
  (-start [_])
  (-stop [_]))

(defprotocol Send
  (-send [_ v]))

(defprotocol Broadcast
  (-broadcast [_ opts]))

(defprotocol Release
  :extend-via-metadata true
  (-release [_]))