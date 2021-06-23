(ns cljctools.vscode.tab-conn.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [goog.string :refer [format]]
   [cljs.reader :refer [read-string]]
   [clojure.pprint :refer [pprint]]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.vscode.tab-conn.chan :as tab-conn.chan]
   #_[cljctools.vscode.protocols :as vscode.p]
   #_[cljctools.vscode.spec :as spec]))

(declare vscode)

(when (exists? js/acquireVsCodeApi)
  (defonce vscode (js/acquireVsCodeApi)))

(defn create-proc-conn
  [channels opts]
  (let [{:keys [::tab-conn.chan/send|]} channels]
    (.addEventListener js/window "message"
                       (fn [ev]
                         #_(println ev.data)
                         (tab-conn.chan/op
                          {::op.spec/op-key ::tab-conn.chan/tab-recv}
                          (::tab-conn.chan/recv| channels)
                          (read-string ev.data))))
    (go
      (loop []
        (when-let [v (<! send|)]
          (.postMessage vscode (pr-str v)))
        (recur))
      (println "; proc-conn go-block exiting"))
    #_(reify
        p/Connect
        (-connect [_])
        (-disconnect [_])
        (-connected? [_]))))





