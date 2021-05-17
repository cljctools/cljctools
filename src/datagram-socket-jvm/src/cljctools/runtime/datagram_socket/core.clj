(ns cljctools.runtime.datagram-socket.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [clojure.spec.alpha :as s]

   [cljctools.runtime.datagram-socket.spec :as datagram-socket.spec]
   [cljctools.runtime.datagram-socket.protocols :as datagram-socket.protocols])
  (:import
   (java.net DatagramSocket InetSocketAddress DatagramPacket)))

(set! *warn-on-reflection* true)

(s/def ::packet-size int?)

(defn create
  [{:as opts
    :keys [::packet-size
           ::datagram-socket.spec/port
           ::datagram-socket.spec/host
           ::datagram-socket.spec/on-listening
           ::datagram-socket.spec/on-message
           ::datagram-socket.spec/on-error]
    :or {port 6881
         host "0.0.0.0"
         packet-size 512}}]
  {:post [(s/assert ::datagram-socket.spec/socket %)]}
  (let [stateA (atom {})

        ^DatagramSocket raw-socket (DatagramSocket. nil)

        socket
        ^{:type ::datagram-socket.spec/socket}
        (reify
          datagram-socket.protocols/Socket
          (listen*
            [_]
            (a/thread
              (try
                (.bind raw-socket (InetSocketAddress. ^String host ^int port))
                (on-listening)
                (loop []
                  (let [^bytes byte-arr (byte-array packet-size)
                        ^DatagramPacket packet (DatagramPacket. byte-arr packet-size)
                        _ (.receive raw-socket packet)]
                    (on-message (.getData packet) {:host (.. packet (getAddress) (getHostAddress))
                                                   :port (.getPort packet)}))
                  (recur))
                (catch Exception ex
                  (on-error ex)))))
          (send*
            [_ byte-arr {:keys [host port]}]
            (let [^DatagramPacket packet (DatagramPacket.
                                          ^bytes byte-arr
                                          ^int (alength ^bytes byte-arr)
                                          (InetSocketAddress. ^String host ^int port))]
              (.send raw-socket packet)))
          (close*
            [_]
            (.close raw-socket))
          clojure.lang.IDeref
          (deref [_] @stateA))]

    (reset! stateA {:raw-socket raw-socket
                    :opts opts})
    socket))


(comment
  
  
  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.runtime/datagram-socket-jvm {:local/root "./runtime/src/datagram-socket-jvm"}}}'
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    (require '[cljctools.runtime.datagram-socket.core :as datagram-socket.core]))
  
  (do
    (def c| (chan 10))
    (go (loop []
          (when-let [v (<! c|)]
            (println :c v)
            (recur))))
    (->
     (a/thread
       (try
         (loop [i 3]
           (when (= i 0)
             (throw (ex-info "error" {})))
           (Thread/sleep 1000)
           (println i)
           (put! c| i)
           (recur (dec i)))
         (catch Exception e
           :foo)))
     (take! prn)))
  
  ;
  )