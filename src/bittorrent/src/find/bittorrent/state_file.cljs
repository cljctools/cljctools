(ns find.bittorrent.state-file
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   [clojure.walk]
   [cognitect.transit :as transit]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]))

(defonce fs (js/require "fs-extra"))
(defonce path (js/require "path"))


(def transit-write
  (let [handlers {js/Buffer
                  (transit/write-handler
                   (fn [buffer] "js/Buffer")
                   (fn [buffer] (.toString buffer "hex")))
                  cljs.core.async.impl.channels/ManyToManyChannel
                  (transit/write-handler
                   (fn [c|] "ManyToManyChannel")
                   (fn [c|] nil))}
        writer (transit/writer
                :json-verbose
                {:handlers handlers})]
    (fn [data]
      (transit/write writer data))))

(def transit-read
  (let [handlers {"js/Buffer"
                  (fn [string] (js/Buffer.from string "hex"))
                  "ManyToManyChannel"
                  (fn [string] nil)}
        reader (transit/reader
                :json-verbose
                {:handlers handlers})]
    (fn [data]
      (transit/read reader data))))

(defn load-state
  [data-dir]
  (go
    (try
      (let [state-filepath (.join path data-dir "state/" "find.app.bittorrent.edn")]
        (when (.pathExistsSync fs state-filepath)
          (let [data-string (-> (.readFileSync fs state-filepath)
                                (.toString "utf-8"))]
            (transit-read data-string))))
      (catch js/Error error (println ::error-loading-state error)))))

(defn save-state
  [data-dir state]
  (go
    (try
      (let [state-dir (.join path data-dir "state/")
            state-filepath (.join path state-dir "find.app.bittorrent.edn")
            data-string (transit-write state)]
        (.ensureDirSync fs state-dir)
        (.writeFileSync fs state-filepath data-string))
      (catch js/Error error (println ::error-saving-state error)))))

(comment

  (extend-protocol IPrintWithWriter
    js/Buffer
    (-pr-writer [buffer writer _]
      (write-all writer "#js/buffer \"" (.toString buffer) "\"")))

  (cljs.reader/register-tag-parser!
   'js/buffer
   (fn [value]
     (js/Buffer.from value)))

  (cljs.reader/read-string

   "#js/buffer \"96190f486de62449099f9caf852964b2e12058dd\"")

  (println (cljs.reader/read-string {:readers {'foo identity}} "#foo :asdf"))

  ;
  )