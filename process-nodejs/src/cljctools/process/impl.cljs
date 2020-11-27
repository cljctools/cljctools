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
(def Console (.-Console (js/require "console")))
(def colors (js/require "colors/safe"))

(defn spawn
  [cmd args cp-opts & opts]
  (let [process (.spawn child_process cmd args cp-opts)
        exit| (chan 1)
        stdout| (chan (sliding-buffer 1024))
        stderr| (chan (sliding-buffer 1024))
        {:keys [::process.spec/color
                ::process.spec/process-name] :or {color "black"
                                                  process-name ""}} opts]
    (.on process.stdout "data" (fn [buffer]
                                 #_(println "buffer")
                                 #_(doseq [line (str/split-lines (.toString buffer))]
                                     (js/console.log
                                      (colors.green
                                       (format "%s: %s"
                                               process-name
                                               (.toString buffer)))))
                                 (js/console.log (.toString buffer))
                                 (put! stdout| (.toString buffer))))
    (.on process "close" (fn [code]
                           (put! exit| code)
                           (close! exit|)
                           #_(js/console.log (format "process exited with code %s" code))
                           #_(println (format "process exited with code %s" code))))
    {::process.spec/process process
     ::process.chan/exit| exit|
     ::process.chan/stdout| stdout|
     ::process.chan/stderr| stderr|}))



(comment

  (spawn "ls" #js [] (clj->js {"stdio" #_["pipe"] ["pipe" js/process.stdout js/process.stderr]
                               "detached" true}))

  (.spawn child_process "ls -a"  (clj->js {"stdio" ["pipe" js/process.stdout js/process.stderr]
                                           "detached" false
                                           "shell" "/bin/bash"}))

  (go
    (let [p (spawn "ls" #js [] (clj->js {"stdio" ["pipe"]
                                         "detached" true})
                   ::process.spec/color "green"
                   ::process.spec/process-name "ls")]
      (<! (::process.chan/exit| p))
      (println "output:")
      (close! (::process.chan/stdout| p))
      (println (<! (a/into [] (::process.chan/stdout| p))))))

  ;;
  )

(comment

  (def console (Console. (clj->js {"stdout" js/process.stdout
                                   "stderr" js/process.stderr})))

  (.log console "3")

  ;;
  )

