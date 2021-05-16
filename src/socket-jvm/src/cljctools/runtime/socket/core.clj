(ns cljctools.runtime.socket.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.spec.alpha :as s]

   [cljctools.runtime.socket.spec :as socket.spec]
   [cljctools.runtime.socket.protocols :as socket.protocols])
  (:import
   (java.io InputStream OutputStream)
   (java.net Socket InetSocketAddress)))

(set! *warn-on-reflection* true)

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
           ::socket.spec/on-error]
    :or {time-out 0}}]
  {:pre [(s/assert ::opts %)]
   :post [(s/assert ::socket.spec/socket %)]}
  (let [stateA (atom {})

        ^Socket raw-socket (Socket.)
        ^InputStream in (.getInputStream raw-socket)
        ^OutputStream out (.getOutputStream raw-socket)

        socket
        ^{:type ::socket.spec/socket}
        (reify
          socket.protocols/Socket
          (connect*
            [_]
            (a/thread
              (try
                (.connect raw-socket (InetSocketAddress. ^String host ^int port))
                (.setSoTimeout raw-socket ^int time-out)
                (on-connected)
                (loop []
                  (let [^bytes byte-arr (.readAllBytes in)]
                    (on-message byte-arr))
                  (recur))
                (catch Exception ex
                  (on-error ex)))))
          (send*
            [_ byte-arr]
            (.write out ^bytes byte-arr))
          (close*
            [_]
            (.close raw-socket))
          clojure.lang.IDeref
          (deref [_] @stateA))]

    (reset! stateA {:raw-socket raw-socket
                    :opts opts})
    socket))


(comment
  
  
  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.runtime/socket-jvm {:local/root "./runtime/src/socket-jvm"}}}'
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    (require '[cljctools.runtime.socket.core :as socket.core]))
  
  
  ;
  )