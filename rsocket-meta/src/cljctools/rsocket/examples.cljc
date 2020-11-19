(ns cljctools.rsocket.examples
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string :as str]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   #?(:cljs [cljs.core.async.interop :refer-macros [<p!]])
   #?(:cljs [goog.string.format :as format])
   #?(:cljs [goog.string :refer [format]])
   #?(:cljs [goog.object])
   #?(:cljs [clojure.string :as str])
   #?(:cljs [cljs.reader :refer [read-string]])

   [cljctools.rsocket.spec :as rsocket.spec]
   [cljctools.rsocket.chan :as rsocket.chan]
   [cljctools.rsocket.impl :as rsocket.impl]
   [cljctools.rsocket.protocols :as rsocket.protocols]))

(comment

  (do

    (def transport #_::rsocket.spec/tcp ::rsocket.spec/websocket)
    
    (def accepting-channels (rsocket.chan/create-channels))
    (def initiating-channels (rsocket.chan/create-channels))

    (def accepting (rsocket.impl/create-proc-ops
                    accepting-channels
                    {::rsocket.spec/connection-side ::rsocket.spec/accepting
                     ::rsocket.spec/host "localhost"
                     ::rsocket.spec/port 7000
                     ::rsocket.spec/transport transport}))

    (def initiating (rsocket.impl/create-proc-ops
                     initiating-channels
                     {::rsocket.spec/connection-side ::rsocket.spec/initiating
                      ::rsocket.spec/host "localhost"
                      ::rsocket.spec/port 7000
                      ::rsocket.spec/transport transport}))

    (def accepting-ops| (chan 10))
    (def initiating-ops| (chan 10))

    (pipe (::rsocket.chan/requests| accepting-channels) accepting-ops|)
    (pipe (::rsocket.chan/requests| initiating-channels) initiating-ops|)

    (go (loop []
          (when-let [value (<! accepting-ops|)]
            (let [{:keys [::op.spec/out|]} value]
              (println (format "accepting side receives request:"))
              (println (dissoc value ::op.spec/out|))
              (put! out| {::accepting-sends ::any-kind-of-map-value}))
            (recur))))

    (go (loop []
          (when-let [value (<! initiating-ops|)]
            (let [{:keys [::op.spec/out|]} value]
              (println (format "initiating side receives request:"))
              (println (dissoc value ::op.spec/out|))
              (put! out| {::intiating-sends ::any-kind-of-map-value}))
            (recur))))
    ;
    )

  (go
    (let [out| (chan 1)]
      (put! (::rsocket.chan/ops| accepting-channels) {::op.spec/op-key ::hello-from-accepting
                                                      ::op.spec/op-type ::op.spec/request-response
                                                      ::op.spec/op-orient ::op.spec/request
                                                      ::op.spec/out| out|})
      (println (<! out|))
      (println "request go-block exists")))


  ;;
  )