(ns cljctools.bytes.core
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.spec :as bytes.spec]
   [cljctools.bytes.impl :as bytes.impl]))

(defn random-bytes
  [length]
  (bytes.impl/random-bytes length))