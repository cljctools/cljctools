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

(deftest ^{:example true} core-async-blocking-put-in-catch
  (testing "(>! chan| foo) inside a catch"
    (let [result| (chan 10)
          data {:a 1}]
      (go
        (try
          (throw (ex-info "Test error" data))
          (catch Exception ex
            (do
              (>! result| (ex-data ex))))))
      (is (= (<!! result|) data)))))

(deftest ^{:example true} hello-world-echo
  (testing "io/read io/write"
    (let [server-recv| (chan 10)
          server-send| (chan 10)
          socket-recv| (chan 10)
          socket-send| (chan 10)
          server-send|mult (mult server-send|)
          log| (chan 100)
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
                    (put! log| [:will-send id data])
                    (.write writer (pr-str data))
                    (.newLine writer)
                    (.flush writer)
                    (recur))))
              (put! log| [:closing-socket-send id])))

          socket-recv
          (fn socket-recv
            [id socket recv|]
            (go
              (with-open [reader (io/reader socket)]
                (try
                  (loop []
                    (let [line (.readLine reader)]
                      (put! log| [:socket-recv id (read-string line)])
                      (put! recv| (read-string line))
                      (recur)))
                  (catch IOException ex
                    (put! log| [:IOException :readline id]))))
              (put! log| [:closing-socket-recv id])))

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
            (put! log| [:server-recv data])
            (put! server-send| data)
            (recur)))
        (put! log| [:server-stops-recv]))
      (go
        (let [socketsA (atom #{})]
          (try
            (loop []
              (when-not (.isClosed socket-server)
                (let [socket (.accept socket-server)]
                  (put! log| [:socket-connected])
                  (socket-send :socket-client socket (tap server-send|mult (chan 10)))
                  (socket-recv :socket-client socket server-recv|)
                  (swap! socketsA conj socket)
                  (recur))))
            (catch IOException ex (put! log| [:IOException :socket-accept]))
            (finally
              (do
                (put! log| [:closing-sockets])
                (doseq [socket @socketsA]
                  (.close socket)))))))
      (let [data {:foo 42}
            socket| (go
                      (with-open [socket (doto (java.net.Socket.)
                                           (.connect (java.net.InetSocketAddress. host port)))]
                        (<! (a/merge [(socket-send :socket socket socket-send|)
                                      (socket-recv :socket socket socket-recv|)]))
                        (put! log| [:will-close-socket])))]
        (put! socket-send| data)
        (let [echo (<!! socket-recv|)]
          (put! log| [:echo echo])
          (put! log| [:release])
          (release)
          (<!! socket|)
          (close! log|)
          (let [log  (<!! (a/into [] log|))]
            #_(pprint log)
            (is (= #{[:socket-connected]
                     [:will-send :socket data]
                     [:socket-recv :socket-client data]
                     [:server-recv data]
                     [:will-send :socket-client data]
                     [:socket-recv :socket data]
                     [:echo echo]
                     [:release]
                     [:server-stops-recv]
                     [:closing-socket-send :socket-client]
                     [:IOException :socket-accept]
                     [:closing-sockets]
                     [:closing-socket-send :socket]
                     [:IOException :readline :socket-client]
                     [:IOException :readline :socket]
                     [:closing-socket-recv :socket-client]
                     [:closing-socket-recv :socket]
                     [:will-close-socket]} (into #{} log)))))))))