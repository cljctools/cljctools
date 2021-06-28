(ns cljctools.codec.runtime.core)

; requires js/Buffer

(defn hex-decode
  [string]
  (js/Buffer.from string "hex"))

(defn hex-encode-string
  [buffer]
  (.toString buffer "hex"))