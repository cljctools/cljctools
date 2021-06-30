(ns cljctools.codec.runtime.core)

; requires js/Buffer

(defn hex-to-bytes
  [string]
  (js/Buffer.from string "hex"))

(defn hex-to-string
  [buffer]
  (.toString buffer "hex"))