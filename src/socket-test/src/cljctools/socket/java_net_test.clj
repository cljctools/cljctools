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

          release (fn []
                    (close! server-recv|)
                    (close! server-send|)
                    (close! socket-recv|)
                    (close! socket-send|)
                    (.close socket-server))]
      (println ::port port)
      (go
        (loop []
          (when-let [line (<! server-recv|)]
            (let [data (read-string line)]
              (println ::server-recv data)
              (put! server-send| data))
            (recur))))
      (go
        (loop []
          (let [socket (.accept socket-server)
                writer (io/writer socket)
                reader (io/reader socket)]
            (go
              (let [server-send|tap (tap server-send|mult (chan 10))]
                (loop []
                  (when-let [data (<! server-send|tap)]
                    (println ::will-send data)
                    (.write writer (pr-str data))
                    (.newLine writer)
                    (.flush writer)
                    (recur))))
              (do
                (println ::closing-client-socket)
                (.close socket)
                (.close writer)
                (.close reader)))
            (go
              (loop []
                (let [line (.readLine reader)]
                  (put! server-recv| line)
                  (recur)))))
          (recur)))
      (go
        (let [socket (doto (java.net.Socket.)
                       (.connect (java.net.InetSocketAddress. host port)))
              writer (io/writer socket)
              reader (io/reader socket)]
          (println (type writer))
          (println (type reader))
          (go
            (loop []
              (when-let [data (<! socket-send|)]
                (.write writer (pr-str (merge data)))
                (.newLine writer)
                (.flush writer)
                (recur)))
            (do
              (println ::closing-socket)
              (.close socket)
              (.close writer)
              (.close reader)))
          (go
            (loop []
              (let [line (.readLine reader)]
                (put! socket-recv| (read-string line))
                (recur))))))
      (let [data {::foo 42}]
        (put! socket-send| data)
        (let [echo (<!! socket-recv|)]
          (println ::echo echo)
          (is (=  data echo)))
        (println ::release)
        (release)))))
