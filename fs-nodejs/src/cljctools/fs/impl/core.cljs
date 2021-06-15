(ns cljctools.fs.impl.core
  (:refer-clojure :exclude [remove])
  (:require
   [cljctools.fs.protocols :as fs.protocols]))

(defonce fs (js/require "fs-extra"))
(defonce path (js/require "path"))

(defn path-join
  [& args]
  (->>
   (apply (.-join path) args)
   (.resolve path)))

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

(deftype TWriter [write-stream]
  fs.protocols/PWriter
  (write*
    [_ string]
    (.write write-stream string))
  (close*
    [_]
    (.end write-stream)))

(defn writer
  [filepath & {:keys [append] :as opts}]
  (let [options (if append
                  #js {:flags "a"}
                  #js {})
        write-stream (.createWriteStream fs filepath options)]
    (TWriter.
     write-stream)))

(defn remove
  ([filepath]
   (remove filepath true))
  ([filepath silently?]
   (.removeSync fs filepath)))