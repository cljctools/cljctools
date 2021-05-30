(ns cljctools.protobuf.core
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]))

; should be clojure data -> bytes out -> data, no .proto files, compilers and other crap - just data -> bytes -> data -> bytes

