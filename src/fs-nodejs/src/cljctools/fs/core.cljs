(ns cljctools.fs.core
  (:refer-clojure :exclude []))

(defonce fs (js/require "fs-extra"))
(defonce path (js/require "path"))

(defn path-join
  [& args]
  (apply (.-join path) args))

(defn path-exists?
  [filepath]
  (.pathExistsSync fs filepath))

(defn read-file
  [filepath]
  (.readFileSync fs filepath))

(defn write-file
  [filepath data-string]
  (.writeFileSync fs filepath data-string))

(defn make-parents
  [filepath]
  (.ensureDirSync (.dirname path filepath)))