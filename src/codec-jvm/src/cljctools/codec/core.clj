(ns cljctools.codec.core
  (:import
   (org.apache.commons.codec.binary Hex)))

(set! *warn-on-reflection* true)

(defn hex-decode
  [^String string]
  (Hex/decodeHex string))

(defn hex-encode-string
  [^bytes byte-arr]
  (Hex/encodeHexString byte-arr))
