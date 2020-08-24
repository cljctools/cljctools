(ns cljctools.vscode.tab-conn
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [goog.string :refer [format]]
   [cljs.reader :refer [read-string]]
   [clojure.pprint :refer [pprint]]

   [cljctools.vscode.protocols :as p]
   [cljctools.vscode.spec :as spec]))

#_(declare vscode)

(when (exists? js/acquireVsCodeApi)
  (defonce vscode (js/acquireVsCodeApi)))

(defn create-channels
  []
  (let [recv| (chan (sliding-buffer 10))
        recv|m (mult recv|)
        send| (chan 10)
        send|m (mult send|)]
    {::spec/recv| recv|
     ::spec/recv|m recv|m
     ::spec/send| send|
     ::spec/send|m send|m}))

(defn create-proc-conn
  [channels ctx]
  (let [{:keys [::spec/send| ::spec/send|m ::spec/recv|]} channels
        send|t (tap send|m (chan 10))]
    (.addEventListener js/window "message"
                       (fn [ev]
                         #_(println ev.data)
                         (put! recv| (read-string ev.data))))
    (go
      (loop []
        (when-let [v (<! send|t)]
          (.postMessage vscode (pr-str v)))
        (recur))
      (println "; proc-conn go-block exiting"))
    (reify
      p/Connect
      (-connect [_])
      (-disconnect [_])
      (-connected? [_])
      p/Send
      (-send [_ v] (put! send| v)))))

(defn send
  [conn v]
  (p/-send conn v))




