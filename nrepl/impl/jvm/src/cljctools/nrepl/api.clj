(ns cljctools.nrepl.api
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljctools.nrepl.protocols :as p]
   [cljctools.nrepl.spec :as sp]
   [nrepl.server]
   [nrepl.misc]
   [nrepl.middleware]
   [nrepl.transport]
   [cider.nrepl]
   #_[cider.piggieback]))


; how to create a var dynamically
; https://stackoverflow.com/questions/2486752/in-clojure-how-to-define-a-variable-named-by-a-string

#_(doseq [x ["a" "b" "c"]]
    (intern *ns* (symbol x) 42))

#_(doall (for [x ["a" "b" "c"]] (eval `(def ~(symbol x) 42))))

(defn create-var1
  ;; I used clojure.lang.Var/intern in the original answer,
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
  (let [{:keys [::middlewares]} opts]
    (apply nrepl.server/default-handler (concat
                                         (map
                                          cider-resolve-or-fail
                                          cider.nrepl/cider-middleware)
                                         middlewares))))
(defn create-nrepl-enter-middleware*
  "Intercepts nrepl messages.
   Take any nrepl incoming op and put it onto a channel.
   Wait for it to be processed by the system and be put back onto out|.
   Pass message to the next handler"
  [{:keys [ops|]}]
  (let [var-name (gensym "nrepl-enter-middleware-")
        middleware-fn (fn [handler]
                        (fn [{:keys [op transport] :as msg}]
                          (let [out| (chan 1)]
                            (put! ops| {:op ::sp/nrepl-enter :msg msg :out| out|})
                            #_(println "nrepl-enter-middleware*" (keys msg))
                            #_(println (select-keys msg [:code :stderr :op]))
                            (take! out| (fn [msg]
                                          (handler msg)
                                          #_(t/send transport (response-for msg :status :done :time (System/currentTimeMillis)))
                                          (close! out|))))))
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

#_(defn create-nrepl-leave-middleware*
    "Intercepts nrepl messages.
   Take any nrepl incoming op and put it onto a channel.
   Wait for it to be processed by the system and be put back onto out|.
   Pass message to the next handler"
    [{:keys [ops|]}]
    (let [var-name (gensym "nrepl-leave-middleware-")
          middleware-fn (fn [handler]
                          (fn [{:keys [op transport] :as msg}]
                            (let [out| (chan 1)]
                              (put! ops| {:op ::sp/nrepl-leave :msg msg :out| out|})
                              (println "nrepl-leave-middleware*" (keys msg))
                              (println (select-keys msg [:code :stderr :op]))
                              (take! out| (fn [msg]
                                            (handler msg)
                                            #_(t/send transport (response-for msg :status :done :time (System/currentTimeMillis)))
                                            (close! out|))))))
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

(defn create-channels
  []
  (let [ops| (chan 10)
        ops|m (mult ops|)
        nrepl-enter| (chan (sliding-buffer 10))
        nrepl-enter|m (mult nrepl-enter|)
        nrepl-leave| (chan (sliding-buffer 10))
        nrepl-leave|m (mult nrepl-leave|)
        op| (chan (sliding-buffer 10))
        op|m (mult op|)]
    {::ops| ops|
     ::ops|m ops|m
     ::nrepl-enter| nrepl-enter|
     ::nrepl-enter|m nrepl-enter|m
     ::nrepl-leave| nrepl-leave|
     ::nrepl-leave|m nrepl-leave|m
     ::op|  op|
     ::op|m op|m}))

(defn create-proc-ops
  [channels ctx opts]
  (let [{:keys [::ops| ::ops|m ::op
                ::nrepl-enter| ::nrepl-enter|m]} channels
        {:keys [::middlewares ::host ::port]} opts
        ops|t (tap ops|m (chan 10))
        nrepl-enter|t (tap nrepl-enter|m (chan (sliding-buffer 10)))
        middleware-enter (create-nrepl-enter-middleware* {:ops| nrepl-enter|})
        nrepl-handler (create-nrepl-handler* (merge
                                              opts
                                              {::middlewares
                                               (concat middlewares
                                                       [middleware-enter])}))
        state (atom {:server nil})
        start (fn []
                (println (format "starting nREPL server on %s:%s" host port))
                (->>
                 (nrepl.server/start-server
                  :bind host
                  :port port
                  :handler nrepl-handler #_cider-nrepl-handler #_(nrepl-handler))
                 (swap! state assoc :server)))
        stop (fn [server]
               (println (format "stopping nREPL server on %s:%s" host port))
               (nrepl.server/stop-server server)
               (swap! state assoc :server nil))]
    (go
      (loop []
        (when-let [[v port] (alts! [ops|t nrepl-enter|t])]
          (condp = port
            ops|t (condp = (:op v)
                    :start
                    (let [])

                    :stop
                    (let []))
            nrepl-enter|t (let [{:keys [msg out|]} v]
                            (println "nrepl-enter|t" (select-keys msg [:op :code :stderr]))
                            #_(<! (timeout 1000))
                            (put! out| msg))))
        (recur))
      (println "; proc-ops go-block exiting"))
    (reify
      p/Start
      (-start [_] (start))
      (-stop [_] (stop (:server @state)))
      clojure.lang.ILookup
      (valAt [_ k] (.valAt _ k nil))
      (valAt [_ k not-found] (.valAt @state k not-found)))))

(defn start [_]
  (p/-start _))

(defn stop [_]
  (p/-stop _))

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