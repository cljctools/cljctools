(ns cljctools.vscode.tab-conn.chan
  #?(:cljs (:require-macros [cljctools.vscode.tab-conn.chan]))
  (:require
   [clojure.spec.alpha :as s]))

(do (clojure.spec.alpha/check-asserts true))

(s/def ::foo (s/keys :req-un []))

(defn create-channels
  []
  (let [recv| (chan (sliding-buffer 10))
        recv|m (mult recv|)
        send| (chan 10)
        send|m (mult send|)]
    {::recv| recv|
     ::recv|m recv|m
     ::send| send|
     ::send|m send|m}))


(defn tab-send
  [channels value]
  (put! (::send| channels) value))

(defn tab-recv
  [to| value]
  (put! to| value))
