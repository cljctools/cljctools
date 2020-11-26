(ns cljctools.process.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string :as str]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.process.spec :as process.spec]
   [cljctools.process.chan :as process.chan]))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def child_process (js/require "child_process"))

(defn spawn
  [cmd args opts]
  (let [process (.spawn child_process cmd args opts)
        exit| (chan 1)
        stdout| (chan (sliding-buffer 10))
        stderr| (chan (sliding-buffer 10))]
    (.on process.stdout "data" (fn [data]
                                 (put! stdout| data)))
    (.on process "close" (fn [code]
                           (put! exit| code)
                           (close! exit|)
                           #_(println (format "process exited with code %s" code))))
    {::process.spec/process process
     ::process.chan/exit| exit|
     ::process.chan/stdout| stdout|
     ::process.chan/stderr| stderr|}))



(comment

  (spawn "ls" #js [] {} #_(clj->js {"stdio" ["pipe" js/process.stdout js/process.stderr]
                                    "detached" true}))
  
  (.spawn child_process "ls -a"  (clj->js {"stdio" ["inherit"]
                                           "detached" true
                                           "shell" "/bin/bash"}))

  ;;
  )

