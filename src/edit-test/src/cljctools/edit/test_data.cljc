(ns cljctools.edit.test-data
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   #?(:cljs [cljs.reader :refer [read-string]])
   #?(:cljs [goog.string.format])
   #?(:cljs [goog.string :refer [format]])
   [clojure.spec.alpha :as s]

   [clojure.set :refer [subset?]]
   
   #?(:clj [clojure.java.shell :refer [sh]])
   #?(:clj [clojure.java.io :as io])))

#?(:cljs (do
           (def fs (js/require "fs"))
           (def path (js/require "path"))
           (def cp (js/require "child_process"))

           (defn clojure-repo-git-clone
             [pwd tmp-dir]
             (when-not (.existsSync fs (.join path pwd tmp-dir "/clojure"))
               (.execSync cp
                          (str "mkdir -p " tmp-dir " && "
                               "cd " tmp-dir " && "
                               "git clone https://github.com/clojure/clojure"
                               " && " "cd ../")
                          (clj->js {:cwd pwd}))))

           (defn clojure-repo-remove
             [pwd tmp-dir]
             (.execSync cp (format "rm -rf %s" (.join path pwd tmp-dir))
                        (clj->js {:cwd pwd})))
           
           ))

#?(:clj (do

          (defn clojure-repo-git-clone
            [pwd tmp-dir]
            (when-not (.exists (io/file (str pwd "/" tmp-dir "/clojure")))
              (sh
               "sh" "-c"
               (str
                "mkdir -p " tmp-dir
                " && cd " tmp-dir
                " && "
                "git clone https://github.com/clojure/clojure"))))

          (defn clojure-repo-remove
            [pwd tmp-dir]
            (sh
             "sh" "-c"
             (str "rm -rf " tmp-dir)))))

(defn read-file
  [pwd relative-filepath]
  (let [filepath (str pwd "/" relative-filepath)
        file-string
        #?(:clj (slurp (io/file filepath))
           :cljs (->
                  (.readFileSync
                   fs
                   filepath
                   (clj->js {:encoding "utf8"}))
                  (.toString)))]
    file-string))