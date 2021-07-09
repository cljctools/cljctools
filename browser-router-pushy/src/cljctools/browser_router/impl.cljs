(ns cljctools.browser-router.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljs.core.async.impl.protocols :refer [closed?]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [clojure.string :as string]
   [cljs.reader :refer [read-string]]

   [bidi.bidi :as bidi]
   [pushy.core :as pushy]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.browser-router.chan :as browser-router.chan]
   [cljctools.browser-router.spec :as browser-router.spec])
  (:import [goog.net XhrIo EventType WebSocket]
           [goog Uri]
           goog.history.Html5History))


(defn create-proc-ops
  [channels state opts]
  (let [{:keys [::browser-router.chan/ops|
                ::browser-router.chan/evt|]} channels
        {:keys [::browser-router.spec/routes]} opts

        on-pushed (fn [pushed]
                    #_(println "pushed" pushed)
                    (let [{:keys [url route-params handler]} pushed
                          value  {::browser-router.spec/url url
                                  ::browser-router.spec/route-params route-params
                                  ::browser-router.spec/route-key handler}]
                      (put! evt| value)
                      (when state
                        (swap! state merge value))))
        on-parse-url (fn [url]
                       (merge
                        {:url url}
                        (bidi/match-route routes url)))
        history (pushy/pushy on-pushed  on-parse-url)]
    (do
      (pushy/start! history))
    (go
      (loop []
        (when-let [[value port] (alts! [ops|])]
          (condp = port
            ops|
            (condp = (select-keys value [::op.spec/op-key ::op.spec/op-type ::op.spec/op-orient])

              {::op.spec/op-key ::browser-router.chan/set-token
               ::op.spec/op-type ::op.spec/fire-and-forget}
              (let [{:keys [::browser-router.spec/history-token]} value]
                (println ::set-token history-token)
                (pushy/set-token! history history-token)))))
        (recur))
      (pushy/stop! history))))


(comment

  (def channels (browser-router.chan/create-channels))
  (def state (atom {}))

  (def routes ["/" {"" ::page-events
                    "game/" {[::game-id ""] ::page-game}}])

  (def router (create-proc-ops channels state {::browser-router.spec/routes routes}))

  (browser-router.chan/op
   {::op.spec/op-key ::browser-router.chan/set-token
    ::op.spec/op-type ::op.spec/fire-and-forget}
   channels
   {::browser-router.spec/history-token "foo"})
  
  (browser-router.chan/op
   {::op.spec/op-key ::browser-router.chan/set-token
    ::op.spec/op-type ::op.spec/fire-and-forget}
   channels
   {::browser-router.spec/history-token "/game/bar"})


  ;;
  )