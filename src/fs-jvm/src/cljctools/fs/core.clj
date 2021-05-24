(ns cljctools.fs.core
  (:require [clojure.java.io :as io]))

(defn path-join
  [& args]
  (->
   (apply io/file args)
   (.getCanonicalPath)))

(defn path-exists?
  [filepath]
  (.exists (io/file filepath)))

(defn read-file
  [filepath]
  (slurp filepath))

(defn write-file
  [filepath data-string]
  (spit filepath data-string))

(defn make-parents
  [filepath]
  (io/make-parents filepath))