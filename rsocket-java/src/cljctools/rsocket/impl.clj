(ns cljctools.rsocket.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string :as str]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.rsocket.spec :as rsocket.spec]
   [cljctools.rsocket.chan :as rsocket.chan]
   [cljctools.rsocket.protocols :as rsocket.protocols])
  (:import
   io.rsocket.Payload
   io.rsocket.RSocket
   io.rsocket.SocketAcceptor
   io.rsocket.core.RSocketClient
   io.rsocket.core.RSocketConnector
   io.rsocket.core.RSocketServer
   io.rsocket.transport.netty.client.TcpClientTransport
   io.rsocket.transport.netty.server.TcpServerTransport
   io.rsocket.util.DefaultPayload
   java.time.Duration))


(defn create-proc-rsocket
  [channels opts]
  (let [{:keys [::rsocket.chan/ops|
                ::rsocket.chan/evt|m
                ::rsocket.chan/send|
                ::rsocket.chan/recv|]} channels
        rsocket (atom nil)]
    (go
      (loop []
        (when-let [[v port] (alts! [ops|])]
          (condp = port

            ops|
            (condp = (select-keys v [::op.spec/op-key ::op.spec/op-type])

              {::op.spec/op-key  ::rsocket.chan/connect}
              (let [])))
          
          )))
    ))


(comment
  
  
  
  ;;
  )



(defn create-proc-ops
  [channels opts]
  (let [{:keys [::rsocket.chan/ops|
                ::rsocket.chan/evt|m
                ::rsocket.chan/send|
                ::rsocket.chan/recv|]} channels]
    (go
      (loop []
        (when-let [[v port] (alts! [ops|])])))))
