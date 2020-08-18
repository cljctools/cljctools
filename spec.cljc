(ns cljctools.vscode.spec
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test.check.generators :as gen]))

(defmacro op
  [chkey opkey]
  (s/assert ::ch-exists  chkey)
  (s/assert ::op-exists  opkey)
  `~opkey)

(defmacro vl
  [chkey v]
  (s/assert ::ch-exists  chkey)
  `~v)