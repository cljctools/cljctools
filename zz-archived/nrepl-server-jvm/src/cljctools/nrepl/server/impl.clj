(ns cljctools.nrepl.server.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [nrepl.server]
   [nrepl.misc]
   [nrepl.middleware]
   [nrepl.transport]
   [cider.nrepl]
   #_[cider.piggieback]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.nrepl.server.spec :as nrepl.server.spec]
   [cljctools.nrepl.server.chan :as nrepl.server.chan]))


; how to create a var dynamically
; https://stackoverflow.com/questions/2486752/in-clojure-how-to-define-a-variable-named-by-a-string

#_(doseq [x ["a" "b" "c"]]
    (intern *ns* (symbol x) 42))

#_(doall (for [x ["a" "b" "c"]] (eval `(def ~(symbol x) 42))))

(defn create-var1
  ;; Quote: I used clojure.lang.Var/intern in the original answer,
  ;; but as Stuart Sierra has pointed out in a comment,
  ;; a Clojure built-in is available to accomplish the same
  ;; thing
  ([sym] (intern *ns* sym))
  ([sym val] (intern *ns* sym val)))

(defn create-var2
  [sym val]
  (eval `(def ~(symbol sym) ~val)))

(defn- cider-resolve-or-fail [sym]
  (or (resolve sym)
      (throw (IllegalArgumentException. (format "Cannot resolve %s" sym)))))

(defn create-nrepl-handler*
  [opts]
  (let [{:keys [::nrepl.server.spec/middleware]} opts]
    (apply nrepl.server/default-handler (concat
                                         (map
                                          cider-resolve-or-fail
                                          cider.nrepl/cider-middleware)
                                         middleware))))
(defn create-nrepl-enter-middleware*
  "Intercepts nrepl messages.
   Take any nrepl incoming op and put it onto a channel.
   Wait for it to be processed by the system and be put back onto out|.
   Pass message to the next handler"
  [{:keys [::to|]}]
  (let [var-name (gensym "nrepl-enter-middleware-")
        middleware-fn (fn [handler]
                        (fn [{:keys [op transport] :as msg}]
                          (put! to| msg)
                          (handler msg)))
        _ (create-var1 var-name middleware-fn)
        middleware (resolve var-name)]
    (prn middleware)
      ; https://github.com/nrepl/nrepl/blob/master/src/clojure/nrepl/middleware.clj#L20
    (nrepl.middleware/set-descriptor! middleware
                                      {:requires #{}
                                       :expects #{"eval"}
                                       :handles {"nothing"
                                                 {:doc "This middleware handles nothing. It's an interceptor"
                                                  :requires {}
                                                  :optional {"ns" "The namespace in which we want to do lookup. Defaults to `*ns*`."
                                                             "lookup-fn" "The fully qualified name of a lookup function to use instead of the default one (e.g. `my.ns/lookup`)."}
                                                  :returns {}}}})
    middleware))



(defn create-proc-ops
  [channels opts]
  (let [{:keys [::nrepl.server.chan/ops|
                ::nrepl.server.chan/nrepl-enter|]} channels
        {:keys [::nrepl.server.spec/middleware
                ::nrepl.server.spec/host
                ::nrepl.server.spec/port]} opts
        middleware-enter (create-nrepl-enter-middleware* {::to| nrepl-enter|})
        nrepl-handler (create-nrepl-handler* (merge
                                              opts
                                              {::nrepl.server.spec/middleware
                                               (concat middleware
                                                       [middleware-enter])}))
        state (atom {::server nil})
        start-server (fn []
                       (println (format "starting nREPL server on %s:%s" host port))
                       (->>
                        (nrepl.server/start-server
                         :bind host
                         :port port
                         :handler nrepl-handler #_cider-nrepl-handler #_(nrepl-handler))
                        (swap! state assoc ::server)))
        stop-server (fn [server]
                      (println (format "stopping nREPL server on %s:%s" host port))
                      (nrepl.server/stop-server server)
                      (swap! state assoc ::server nil))]
    (go
      (loop []
        (when-let [[v port] (alts! [ops| nrepl-enter|])]
          (condp = port
            ops|
            (condp = (select-keys v [::op.spec/op-key ::op.spec/op-type])

              {::op.spec/op-key ::nrepl.server.chan/start-server}
              (let [{:keys []} v]
                (start-server))

              {::op.spec/op-key ::nrepl.server.chan/stop-server}
              (let [{:keys []} v]
                (stop-server)))

            nrepl-enter|
            (let [{:keys [msg]} v]
              (println ::nrepl-enter| (select-keys msg [:op :code :stderr])))))
        (recur))
      (println "; proc-ops go-block exiting"))
    #_(reify
        p/Start
        (-start [_] (start))
        (-stop [_] (stop (:server @state)))
        clojure.lang.ILookup
        (valAt [_ k] (.valAt _ k nil))
        (valAt [_ k not-found] (.valAt @state k not-found)))))

#_(def cider-nrepl-handler
    "CIDER's nREPL handler."
    (apply nrepl-server/default-handler (map resolve-or-fail mw/cider-middleware)))

#_(defn start-nrepl-server [host port]
    (println (str "; starting nREPL server on " host ":" port))
    (start-server
     :bind host
     :port port
     :handler cider-nrepl-handler #_(nrepl-handler)
     :middleware '[]))


#_(defn nserver-clj
    [{:keys [host port]}]
    (let [srv (nrepl.server/start-server
               :bind host
               :port port
               :handler cider-nrepl-handler #_(nrepl-handler)
               :middleware '[])]
      (println (str "; started nREPL clj on " host ":" port))
      (reify
        NServer
        (-stop [_] (nrepl.server/stop-server srv)))))

#_(defn nserver-cljs
    [{:keys [host port]}]
    (let [srv (nrepl.server/start-server
               :bind host
               :port port
               :handler (nrepl.server/default-handler #'cider.piggieback/wrap-cljs-repl)
               :middleware '[])]
      (println (str "; started nREPL cljs on " host ":" port))
      (reify
        NServer
        (-stop [_] (nrepl.server/stop-server srv)))))