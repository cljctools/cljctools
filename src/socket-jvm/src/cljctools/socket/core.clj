(ns cljctools.socket.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.spec.alpha :as s]

   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols]

   [manifold.deferred :as d]
   [manifold.stream :as sm]
   [aleph.tcp])
  (:import
   (java.net InetSocketAddress)
   (io.netty.bootstrap Bootstrap)
   (io.netty.channel ChannelPipeline)))

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
  {:pre [(s/assert ::opts opts)]
   :post [(s/assert ::socket.spec/socket %)]}
  (let [stateA (atom {})
        streamV (volatile! nil)
        socket
        ^{:type ::socket.spec/socket}
        (reify
          socket.protocols/Socket
          (connect*
            [_]
            (try
              (let [stream @(aleph.tcp/client {:host host
                                               :port port
                                               :insecure? true})]
                (vreset! streamV stream)
                (on-connected)
                (d/loop []
                  (->
                   (sm/take! stream ::none)
                   (d/chain
                    (fn [byte-arr]
                      (when-not (identical? byte-arr ::none)
                        (on-message byte-arr)
                        (d/recur)))))))
              (catch Exception ex
                (on-error ex))))
          (send*
            [_ byte-arr]
            (sm/put! @streamV byte-arr))
          (close*
            [_]
            (sm/close! @streamV))
          clojure.lang.IDeref
          (deref [_] @stateA))]

    (reset! stateA {:streamV streamV
                    :opts opts})
    socket))


(comment
  
  
  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/socket-jvm {:local/root "./cljctools/src/socket-jvm"}}}'
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    (require '[cljctools.socket.core :as socket.core])
    (require '[manifold.deferred :as d])
    (require '[manifold.stream :as sm]))
   

  (def s (sm/stream))
  (sm/consume #(prn %) s)
  (sm/put! s 1)
  
  ;
  )