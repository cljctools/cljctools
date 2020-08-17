(ns cljctools.cljs-self-hosting
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljs.js :as cljs]
   [cljs.env :as env]
   [cljs.reader :refer [read-string]]
   [cljctools.cljs-cache :refer [compile-state-ref]]))

(defn test1
  []
  [(eval '(cljs.core.async/chan 10))
   (let [f (eval '(fn [file-uri] (cljs.core/re-matches #".+\.cljs" file-uri)))]
     (f "abc.cljs"))
   (eval '(re-matches #".+clj" "abc.clj"))
   (apply (eval '(fn abc [a b c] #{a b c})) [1 2 3])])

(comment

  (type compile-state-ref)
  
  (test1)

  ;;
  )