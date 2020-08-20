(ns cljctools.vscode.tab-conn
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [goog.string :refer [format]]
   [cljs.reader :refer [read-string]]
   [clojure.pprint :refer [pprint]]

   [cljctools.vscode.protocols :as p]))

#_(declare vscode)

(when (exists? js/acquireVsCodeApi)
  (defonce vscode (js/acquireVsCodeApi)))

(defn create-channels
  []
  (let [conn-recv| (chan (sliding-buffer 10))
        conn-recv|m (mult conn-recv|)
        conn-send| (chan 10)
        conn-send|m (mult conn-send|)]
    {:conn-recv| conn-recv|
     :conn-recv|m conn-recv|m
     :conn-send| conn-send|
     :conn-send|m conn-send|m}))

(defn create-proc-conn
  [channels ctx]
  (let [{:keys [conn-send| conn-send|m conn-recv|]} channels
        conn-send|t (tap conn-send|m (chan 10))]
    (.addEventListener js/window "message"
                       (fn [ev]
                         #_(println ev.data)
                         (put! conn-recv| (read-string ev.data))))
    (go
      (loop []
        (when-let [v (<! conn-send|t)]
          (.postMessage vscode (pr-str v)))
        (recur))
      (println "; proc-conn go-block exiting"))
    (reify
      p/Connect
      (-connect [_])
      (-disconnect [_])
      (-connected? [_])
      p/Send
      (-send [_ v] (put! conn-send| v)))))

(defn send
  [conn v]
  (p/-send conn v))




