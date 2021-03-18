(ns cljctools.socket.nodejs-net-test
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   [cljs.reader :refer [read-string]]
   [goog.string.format]
   [goog.string :refer [format]]
   [clojure.spec.alpha :as s]

   [clojure.set :refer [subset?]]

   [clojure.spec.gen.alpha :as sgen]
   [clojure.spec.test.alpha :as stest]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test :refer [is run-all-tests async testing deftest run-tests]]

   [cljctools.socket.protocols :as socket.protocols]
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.core :as socket.core]
   [cljctools.socket.nodejs-net.core :as socket.nodejs-net.core]))

(def net (js/require "net"))

(deftest ^{:integration true} echo
  (testing "Start server, send data and read it back, do a few rounds"
    (let [log| (chan 10)
          server-recv| (chan 10)
          server-send| (chan 10)
          socket (socket.core/create {::socket.spec/id :foo
                                      ::socket.spec/connect? false})
          {:keys [::socket.spec/send|
                  ::socket.spec/recv|mult]} @socket
          socketsA (atom #{})
          server (.createServer net)
          release (fn []
                    (close! server-recv|)
                    (close! server-send|)
                    (socket.protocols/close* socket)
                    (.close server))]
      (doto server
        (.on "connection" (fn [raw-socket]
                            (let []
                              (put! log| [:client-connected])
                              (doto raw-socket
                                (.on "data"
                                     (fn [data]
                                       (put! log| [:server-data (read-string (.toString data))])
                                       (put! server-recv| (read-string (.toString data)))))
                                (.on  "end"
                                      (fn []
                                        (put! log| [:client-disconnected]))))
                              (swap! socketsA conj raw-socket))))
        (.on "listening" (fn []
                           (let [port (.-port (.address server))
                                 host (.-address (.address server))]
                             (socket.protocols/connect*
                              socket
                              (socket.nodejs-net.core/create-opts
                               {::socket.spec/host host
                                ::socket.spec/port port})))))
        (.listen (clj->js {:host "0.0.0.0"
                           :port 0})))
      (go
        (loop []
          (when-let [value (<! server-recv|)]
            (put! log| [:server-recv value])
            (put! server-send| value)
            (recur))))
      (go
        (loop []
          (when-let [value (<! server-send|)]
            (doseq [raw-socket @socketsA]
              (put! log| [:server-send value])
              (.write raw-socket (pr-str value)))
            (recur))))
      (async done
             (go
               (let [data {:bar :baz}
                     recv|tap (tap recv|mult (chan 10))
                     _ (>! send| (pr-str data))
                     echo (read-string (<! recv|tap))]
                 (put! log| [:echo echo])
                 (release)
                 (close! log|)
                 (let [log (<! (a/into [] log|))]
                   (is (= #{[:client-connected]
                            [:server-data data]
                            [:server-recv data]
                            [:server-send data]
                            [:echo echo]} (into #{} log)))
                   (done))))))))