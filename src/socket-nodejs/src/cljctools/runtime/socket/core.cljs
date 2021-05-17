(ns cljctools.runtime.socket.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.spec.alpha :as s]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.string]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]

   [cljctools.runtime.socket.spec :as socket.spec]
   [cljctools.runtime.socket.protocols :as socket.protocols]))

(defonce net (js/require "net"))

(s/def ::opts (s/keys :req [::socket.spec/port
                            ::socket.spec/host
                            ::socket.spec/on-connected
                            ::socket.spec/on-message
                            ::socket.spec/on-error]
                      :opt [::socket.spec/time-out]))

(defn create
  [{:as opts
    :keys [::socket.spec/port
           ::socket.spec/host
           ::socket.spec/time-out
           ::socket.spec/on-connected
           ::socket.spec/on-message
           ::socket.spec/on-error]}]
  {:pre [(s/assert ::opts %)]
   :post [(s/assert ::socket.spec/socket %)]}
  (let [stateA (atom {})
        
        raw-socket (net.Socket.)

        socket
        ^{:type ::socket.spec/socket}
        (reify
          socket.protocols/Socket
          (connect*
            [_]
            (go
              (try
                (doto raw-socket
                  (.on "connect" (fn []
                                   (on-connected)))
                  (.on "data" (fn [buffer]
                                (on-message buffer)))
                  (.on "error" (fn [error]
                                 (on-error error))))
                (when time-out
                  (.setTimeout raw-socket time-out))
                (.connect raw-socket (clj->js
                                      (select-keys opts [::socket.spec/host
                                                         ::socket.spec/port])))
                (catch js/Error error
                  (on-error error)))))
          (send*
            [_ buffer]
            (.write raw-socket buffer))
          (close*
            [_]
            (.end raw-socket))
          cljs.core/IDeref
          (-deref [_] @stateA))]

    (reset! stateA {:raw-socket raw-socket
                    :opts opts})
    socket))


(comment
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.runtime/socket-meta {:local/root "./runtime/src/socket-meta"}
                      github.cljctools.runtime/socket-nodejs {:local/root "./runtime/src/socket-nodejs"}}}' \
  -M -m cljs.main --repl-env node --repl
  
  (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                              pub sub unsub mult tap untap mix admix unmix pipe
                                              timeout to-chan  sliding-buffer dropping-buffer
                                              pipeline pipeline-async]])
  
  (require '[cljctools.runtime.socket.core :as socket.core])
  
  
  ;
  )