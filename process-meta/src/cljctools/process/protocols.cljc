(ns cljctools.process.protocols)

(defprotocol Process
  :extend-via-metadata true
  (-kill [_] [_ signal]))