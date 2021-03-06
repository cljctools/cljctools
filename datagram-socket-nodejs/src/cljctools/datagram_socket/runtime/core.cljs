(ns cljctools.datagram-socket.runtime.core
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

   [cljctools.datagram-socket.spec :as datagram-socket.spec]
   [cljctools.datagram-socket.protocols :as datagram-socket.protocols]))

(defonce dgram (js/require "dgram"))

(defn create
  [{:as opts
    :keys [::datagram-socket.spec/port
           ::datagram-socket.spec/host
           ::datagram-socket.spec/evt|
           ::datagram-socket.spec/msg|
           ::datagram-socket.spec/ex|]
    :or {port 6881
         host "0.0.0.0"}}]
  {:post [(s/assert ::datagram-socket.spec/socket %)]}
  (let [raw-socket (.createSocket dgram "udp4")

        socket
        ^{:type ::datagram-socket.spec/socket}
        (reify
          datagram-socket.protocols/Socket
          (listen*
           [t]
           (go
             (try
               (doto raw-socket
                 (.on "listening" (fn []
                                    (put! evt| {:op :listening})))
                 (.on "message" (fn [buffer rinfo]
                                  (put! msg| {:msgBA buffer
                                              :host (.-address rinfo)
                                              :port (.-port rinfo)})))
                 (.on "error" (fn [error]
                                (put! ex| error))))
               (.bind raw-socket port host)
               (catch js/Error error
                 (put! ex| error)
                 (datagram-socket.protocols/close* t)))))
          (send*
            [_ buffer {:keys [host port]}]
            (.send raw-socket buffer 0 (.-length buffer) port host))
          (close*
            [_]
            (.close raw-socket))
          cljs.core/IDeref
          (-deref [_] raw-socket))]

    socket))


(comment
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/datagram-socket-meta {:local/root "./cljctools/datagram-socket-meta"}
                      github.cljctools/datagram-socket-nodejs {:local/root "./cljctools/datagram-socket-nodejs"}}}' \
  -M -m cljs.main --repl-env node --compile cljctools.datagram-socket.core --repl

  
  (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                              pub sub unsub mult tap untap mix admix unmix pipe
                                              timeout to-chan  sliding-buffer dropping-buffer
                                              pipeline pipeline-async]] :reload)
  
  (require '[cljctools.datagram-socket.core :as datagram-socket.core] :reload)
  
  
  ;
  )