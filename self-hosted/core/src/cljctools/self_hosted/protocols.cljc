(ns cljctools.self-hosted.protocols)

(defprotocol Compiler
  (-init [_ opts])
  (-eval-data [_ opts])
  (-eval-str [_ opts])
  (-compile-js-str [_ opts]))