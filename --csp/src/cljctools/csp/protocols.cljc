(ns cljctools.csp.protocols)


(defprotocol Start
  (-start [_] [_ opts]))

(defprotocol Release
  :extend-via-metadata true
  (-release [_]))
