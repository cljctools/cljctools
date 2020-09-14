(ns cljctools.nrepl.client.impl
  (:require
   [clojure.core.async :as a :refer [<! >!  chan go alt! take! put! offer! poll! alts! pub sub unsub
                                     timeout close! to-chan  mult tap untap mix admix unmix
                                     pipeline pipeline-async go-loop sliding-buffer dropping-buffer]]
   [cljctools.nrepl.client.impl.async]

   [cljctools.nrepl.bencode.impl :as nrepl.bencode.impl]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.net.socket.spec :as net.socket.spec]
   [cljctools.net.socket.chan :as net.socket.chan]

   [cljctools.nrepl.client.spec :as nrepl.client.spec]
   [cljctools.nrepl.client.chan :as nrepl.client.chan]))


(defn create-proc-ops
  [channels opts]
  (let [{:keys [::nrepl.client.chan/ops|
                ::net.socket.chan/evt|m
                ::net.socket.chan/send|
                ::net.socket.chan/recv|]} channels
        nrepl-op-id-key :id
        encode #(nrepl.bencode.impl/encode %)
        decode #(nrepl.bencode.impl/decode %)
        xform-relevant-nrepl-value? (comp
                                     (map (fn [v] (encode v)))
                                     (filter (fn [v] (get v nrepl-op-id-key))))
        recv|* (pipe recv| (chan (sliding-buffer 10) xform-relevant-nrepl-value?))
        recv|p (cljctools.nrepl.client.impl.async/pub recv|* nrepl-op-id-key (fn [_] (sliding-buffer 10)))
        nrepl-op (fn [opts]
                   (let [{:keys [::nrepl.client.spec/done-keys
                                 ::nrepl.client.spec/result-keys
                                 ::nrepl.client.spec/nrepl-op-request-data]
                          :or {done-keys [:status :err]
                               result-keys [:value :err]}} opts
                         topic (str (random-uuid))
                         recv|s (chan 10)
                         _ (sub recv|p topic recv|s)
                         out| (chan 50)
                         release #(do
                                    (close! out|)
                                    (close! recv|s)
                                    (unsub recv|p topic recv|s)
                                    (cljctools.nrepl.client.impl.async/close-topic recv|p topic))
                         error| (chan 1)
                         nrepl-op-request-data (merge nrepl-op-request-data {nrepl-op-id-key topic})]
                     (try
                       (prn "sending")
                       (prn nrepl-op-request-data)
                       (put! send| (encode nrepl-op-request-data))
                       (catch js/Error error (put! error| (ex-info "Error xfroming/sending nrepl op"
                                                                   {::nrepl.client.spec/nrepl-op-request-data nrepl-op-request-data
                                                                    ::nrepl.client.spec/error (str ex)}))))
                     (go
                       (loop [timeout| (timeout 10000)]
                         (alt!
                           recv|s ([v] (when v
                                         (>! out| v)
                                         (if (not-empty (select-keys v done-keys))
                                           (do (release)
                                               (let [nrepl-op-responses (<! (a/into [] out|))]
                                                 (transduce
                                                  (comp
                                                   (keep #(or (not-empty (select-keys % result-keys)) nil))
                                                   #_(mapcat vals))
                                                  merge
                                                  {::nrepl.client.spec/nrepl-op-request-data  nrepl-op-request-data
                                                   ::nrepl.client.spec/nrepl-op-responses nrepl-op-responses}
                                                  nrepl-op-responses)))
                                           (recur timeout|))))
                           timeout| ([v] (do
                                           (release)
                                           (ex-info "Error: nrepl-op timed out"
                                                    {::nrepl.client.spec/nrepl-op-request-data nrepl-op-request-data})))
                           error| ([exinfo] (do
                                              (release)
                                              exinfo)))))))]
    (go
      (loop []
        (when-let [[v port] (alts! [ops|])]
          (condp = port

            ops|
            (condp = (select-keys v [::op.spec/op-key ::op.spec/op-type])

              {::op.spec/op-key  ::nrepl.client.chan/eval
               ::op.spec/op-type ::op.spec/request}
              (let [{:keys [::op.spec/out|
                            ::nrepl.client.spec/code
                            ::nrepl.client.spec/session]} v]
                (->
                 (nrepl-op {::nrepl.client.spec/nrepl-op-request-data {:op "eval"
                                                                       :code code
                                                                       :session session}})
                 (take! (fn [value]
                          (nrepl.client.chan/op
                           {::op.spec/op-key  ::nrepl.client.chan/eval
                            ::op.spec/op-type ::op.spec/response}
                           out|
                           value)))))

              {::op.spec/op-key  ::nrepl.client.chan/clone-session
               ::op.spec/op-type ::op.spec/request}
              (let []
                (->
                 (nrepl-op {::nrepl.client.spec/nrepl-op-request-data {:op "clone"}
                            ::nrepl.client.spec/result-keys [:new-session]})
                 (take! (fn [value]
                          (nrepl.client.chan/op
                           {::op.spec/op-key  ::nrepl.client.chan/clone-session
                            ::op.spec/op-type ::op.spec/response}
                           out|
                           value))))))))
        (recur))
      (println (format "go-block exit %s" ::create-proc-ops)))
    #_(reify
        p/Connect
        (-connect [_])
        (-disconnect [_] (.terminate socket))
        (-connected? [_] (= socket.readyState WebSocket.OPEN) #_(not socket.connecting))
        p/Send
        (-send [_ v] (put! send| v))
        cljs.core/ILookup
        (-lookup [_ k] (-lookup _ k nil))
        (-lookup [_ k not-found] (-lookup @state k not-found)))))



#_(defn nrepl
  [opts log]
  (let [proc| (chan 10)
        {:keys [id]} opts
        socket|| (net.socket.chan/create-channels)
        socket (net.socket.impl/create-proc-ops socket|| (select-keys opts [:reconnection-timeout :host :port :id]))
        {:keys [send| receive|m status|]} socket
        topic-fn :id
        xf-send #(.encode bencode (clj->js %))
        xf-receive #(as-> % v
                      (.toString v)
                      (.decode bencode v "utf8")
                      (js->clj v :keywordize-keys true))
        receive|t (tap receive|m (chan (sliding-buffer 10)))
        nrepl| (chan (sliding-buffer 10))
        nrepl|p (cljctools.nrepl.client.impl.async/pub nrepl| topic-fn (fn [_] (sliding-buffer 10)))
        lookup (merge opts {:status| status|})]
    (go-loop []
      (when-let [v (<! receive|t)]
        (try
          (let [d (xf-receive v)]
            (prn "receive")
            (prn d)
            (when (:id d)
              (>! nrepl| d)))
          (catch js/Error ex))
        (recur))
      (log (format "nrepl %s xform process exiting" id)))
    #_(reify
        p/Connect
        (-connect [_] (p/-connect socket))
        (-disconnect [_] (p/-disconnect socket))
        (-connected? [_] (p/-connected? socket))
        p/ReplConn

        (-describe [_  opts])
        (-interrupt [_ session opts])
        (-ls-sessions [_])
        (-nrepl-op [_ opts]
          (let [{:keys [done-keys op-data result-keys]
                 :or {done-keys [:status :err]
                      result-keys [:value :err]}} opts
                topic (str (random-uuid))
                nrepl|s (chan 10)
                _ (sub nrepl|p topic nrepl|s)
                res| (chan 50)
                release #(do
                           (close! res|)
                           (close! nrepl|s)
                           (unsub nrepl|p topic nrepl|s)
                           (cljctools.nrepl.client.impl.async/close-topic nrepl|p topic))
                ex| (chan 1)
                req (merge op-data {:id topic})]
            (try
              (prn "sending")
              (prn req)
              (put! send| (xf-send req))
              (catch js/Error ex (put! ex| {:comment "Error xfroming/sending nrepl op" :err-str (str ex)})))
            (go
              (loop [t| (timeout 10000)]
                (alt!
                  nrepl|s ([v] (when v
                                 (>! res| v)
                                 (if (not-empty (select-keys v done-keys))
                                   (do (release)
                                       (let [res (<! (a/into [] res|))]
                                         (transduce
                                          (comp
                                           (keep #(or (not-empty (select-keys % result-keys)) nil))
                                           #_(mapcat vals))
                                          merge
                                          {:req  req
                                           :res res}
                                          res)))
                                   (recur t|))))
                  t| ([v] (do
                            (release)
                            {:comment "Error: -nrepl-op timed out" :req req}))
                  ex| ([ex] (do
                              (release)
                              ex)))))))
        (-close-session [_ session opts])
        (-clone-session [_]
          (let []
            (p/-nrepl-op _ (merge opts {:op-data {:op "clone"} :result-keys [:new-session]}))))
        p/Eval
        (-eval [_ opts]
          (let [{:keys [code  session]} opts]
            (p/-nrepl-op _ (merge opts {:op-data {:op "eval" :code code :session session}}))))
        cljs.core/ILookup
        (-lookup [_ k] (-lookup _ k nil))
        (-lookup [_ k not-found] (-lookup lookup k not-found)))))