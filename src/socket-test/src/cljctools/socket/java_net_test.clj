(ns cljctools.socket.java-net-test
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
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

(deftest arithmetic
  (testing "Arithmetic"
    (testing "with positive integers"
      (is (= 4 (+ 2 2)))
      (is (= 7 (+ 3 4))))
    (testing "with negative integers"
      (is (= -4 (+ -2 -2)))
      (is (= -1 (+ 3 -4))))))

(deftest hello-world
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
          port (.getLocalPort socket-server)]
      (go
        (loop []
          (with-open [socket (.accept socket-server)
                      writer (io/writer socket)
                      reader (io/reader socket)
                      s (java.io.StringWriter.)]
            (go
              (let [server-send|tap (tap server-send|mult (chan 10))]
                (loop []
                  (when-let [data (<! server-send|tap)]
                    (.write writer (pr-str data))
                    (.flush writer)
                    (recur)))))
            (go
              (loop []
                (let []
                  (.write out (pr-str data))
                  (.flush out)
                  (recur)))))))
      (go
        (let [socket (java.net.Socket.)]
          (.connect (java.net.InetSocketAddress. host port)))
        (with-open [r (io/reader socket)
                    s (java.io.StringWriter.)]
          (io/copy r s))))))
