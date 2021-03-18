(ns cljctools.socket.java-net-test
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! <!! >!! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [clojure.set :refer [subset?]]
   
   [clojure.spec.gen.alpha :as sgen]
   [clojure.spec.test.alpha :as stest]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test :refer [is run-all-tests testing deftest run-tests]]

   [clojure.java.io :as io]

   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols]
   [cljctools.socket.core :as socket.core])
  (:import
   [java.net
    InetSocketAddress
    Socket
    ServerSocket
    SocketException]
   [java.io
    IOException]))

#_(deftest arithmetic
  (testing "Arithmetic"
    (testing "with positive integers"
      (is (= 4 (+ 2 2)))
      (is (= 7 (+ 3 4))))
    (testing "with negative integers"
      (is (= -4 (+ -2 -2)))
      (is (= -1 (+ 3 -4))))))


(deftest hello-world-echo
  (testing "io/read io/write"
    (let [server-recv| (chan 10)
          server-send| (chan 10)
          socket-recv| (chan 10)
          socket-send| (chan 10)
          server-send|mult (mult server-send|)
          host "0.0.0.0"
          socket-server (doto
                         (ServerSocket.)
                          (.bind (java.net.InetSocketAddress. host 0)))
          port (.getLocalPort socket-server)

          socket-send
          (fn socket-send
            [id socket send|]
            (go
              (with-open [writer (io/writer socket)]
                (loop []
                  (when-let [data (<! send|)]
                    (println ::will-send data)
                    (.write writer (pr-str data))
                    (.newLine writer)
                    (.flush writer)
                    (recur))))
              (println ::closing-socket-send id)))

          socket-recv
          (fn socket-recv
            [id socket recv|]
            (go
              (with-open [reader (io/reader socket)]
                (try
                  (loop []
                    (let [line (.readLine reader)]
                      (put! recv| (read-string line))
                      (recur)))
                  (catch IOException ex (println ::readline (ex-message ex)))))
              (println ::closing-socket-recv id)))

          release (fn []
                    (close! server-recv|)
                    (close! server-send|)
                    (close! socket-recv|)
                    (close! socket-send|)
                    (.close socket-server))]
      (println ::port port)
      (go
        (loop []
          (when-let [data (<! server-recv|)]
            (println ::server-recv data)
            (put! server-send| data)
            (recur))))
      (go
        (try
          (loop [socketsA (atom #{})]
            (if-not (.isClosed socket-server)
              (do
                (let [socket (.accept socket-server)]
                  (socket-send ::socket-client socket (tap server-send|mult (chan 10)))
                  (socket-recv ::socket-client socket server-recv|)
                  (recur (swap! socketsA conj socket))))
              (do
                (doseq [socket @socketsA]
                  (.close socket)))))
          (catch IOException ex (println ::socket-accept (ex-message ex)))))
      (let [data {::foo 42}
            socket| (go
                      (with-open [socket (doto (java.net.Socket.)
                                           (.connect (java.net.InetSocketAddress. host port)))]
                        (<! (a/merge [(socket-send ::socket socket socket-send|)
                                      (socket-recv ::socket socket socket-recv|)]))
                        (println ::will-close-socket)))]
        (put! socket-send| data)
        (let [echo (<!! socket-recv|)]
          (println ::echo echo)
          (is (=  data echo)))
        (println ::release)
        (release)
        (<!! socket|)))))
