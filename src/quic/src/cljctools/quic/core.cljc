(ns cljctools.quic.core
  "QUIC protocol, should be a transformation process over a datagram socket channels"
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]))