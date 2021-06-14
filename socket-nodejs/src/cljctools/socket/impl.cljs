(ns cljctools.socket.impl
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

   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols]))

(defonce net (js/require "net"))

(s/def ::opts (s/keys :req [::socket.spec/port
                            ::socket.spec/host
                            ::socket.spec/evt|
                            ::socket.spec/msg|
                            ::socket.spec/ex|]
                      :opt [::socket.spec/time-out]))

(defn create
  [{:as opts
    :keys [::socket.spec/port
           ::socket.spec/host
           ::socket.spec/time-out
           ::socket.spec/evt|
           ::socket.spec/msg|
           ::socket.spec/ex|]}]
  {:pre [(s/assert ::opts opts)]
   :post [(s/assert ::socket.spec/socket %)]}
  (let [raw-socket (net.Socket.)

        socket
        ^{:type ::socket.spec/socket}
        (reify
          socket.protocols/Socket
          (connect*
            [t]
            (go
              (try
                (doto raw-socket
                  (.on "connect" (fn []
                                   (put! evt| {:op :connected})))
                  (.on "data" (fn [buffer]
                                (put! msg| buffer)))
                  (.on "error" (fn [error]
                                 (put! ex| error))))
                (when time-out
                  (.setTimeout raw-socket time-out))
                (.connect raw-socket (clj->js
                                      (select-keys opts [::socket.spec/host
                                                         ::socket.spec/port])))
                (catch js/Error error
                  (put! ex| error)
                  (socket.protocols/close* t)))))
          (send*
            [_ buffer]
            (.write raw-socket buffer))
          (close*
            [_]
            (.end raw-socket))
          cljs.core/IDeref
          (-deref [_] raw-socket))]

    socket))


(comment
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/socket-meta {:local/root "./cljctools/socket-meta"}
                      github.cljctools/socket-nodejs {:local/root "./cljctools/socket-nodejs"}}}' \
  -M -m cljs.main --repl-env node --compile cljctools.socket.core --repl
  
  (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                              pub sub unsub mult tap untap mix admix unmix pipe
                                              timeout to-chan  sliding-buffer dropping-buffer
                                              pipeline pipeline-async]] :reload)
  
  (require '[cljctools.socket.core :as socket.core] :reload)
  
  
  ;
  )