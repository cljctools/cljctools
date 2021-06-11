(ns cljctools.protobuf.core
  "should be clojure data -> bytes out -> data, no .proto files, compilers and other crap - just data -> bytes -> data -> bytes"
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.varint.core :as varint.core]))







(comment

  (require
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.varint.core :as varint.core]
   '[cljctools.protobuf.core :as protobuf.core]
   :reload)




  ;
  )