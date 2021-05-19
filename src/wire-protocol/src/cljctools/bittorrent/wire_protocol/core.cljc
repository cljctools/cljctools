(ns cljctools.bittorrent.wire-protocol.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.bittorrent.bencode.core :as bencode.core]
   [cljctools.bittorrent.spec :as bittorrent.spec]))

(defprotocol Wire
  (handshake* [_ infohash peer-id]))

(defprotocol Extension
  (foo* [_]))

(s/def ::wire #(and
                (satisfies? Wire %)
                #?(:clj (satisfies? clojure.lang.IDeref %))
                #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))

(s/def ::recv| ::channel)
(s/def ::send| ::channel)

(s/def ::create-wire-opts
  (s/keys :req [::send|
                ::recv|]
          :opt []))

(def msg-protocol (bytes.core/to-byte-array "\u0013BitTorrent protocol"))
(def msg-keep-alive (bytes.core/byte-array [0 0 0 0]))
(def msg-choke (bytes.core/byte-array [0 0 0 1 0]))
(def msg-unchoke (bytes.core/byte-array [0 0 0 1 1]))
(def msg-interested (bytes.core/byte-array [0 0 0 1 2]))
(def msg-not-interested (bytes.core/byte-array [0 0 0 1 3]))
(def msg-have (bytes.core/byte-array [0 0 0 5 4]))
(def msg-reserved (bytes.core/byte-array [0 0 0 0 0 0 0 0]))
(def msg-port (bytes.core/byte-array [0 0 0 3 9 0 0]))

(defn buffer-messages
  [{:as opts
    :keys [::recv|
           ::next-msg-length|
           ::msg-buffer|]}]
  (go
    (loop [recv-bufferT (transient [])
           total-size 0]
      (when-let [buffer (<! recv|)]
        (let [next-total-size (+ total-size (bytes.core/size buffer))
              next-msg-length (<! next-msg-length|)]
          (cond
            (== next-total-size next-msg-length)
            (let [msg-buffer (bytes.core/concat (persistent! (conj! recv-bufferT buffer)))]
              (>! msg-buffer| msg-buffer)
              (recur (transient []) 0))

            (>= next-total-size next-msg-length)
            (let [over-buffer (bytes.core/concat (persistent! (conj! recv-bufferT buffer)))
                  msg-buffer (bytes.core/buffer-wrap over-buffer 0 next-msg-length)
                  leftover-buffer (bytes.core/buffer-wrap over-buffer next-msg-length (- next-total-size next-msg-length))]
              (>! msg-buffer| msg-buffer)
              (recur (transient [leftover-buffer]) (bytes.core/size leftover-buffer)))

            :else
            (recur (conj! recv-bufferT buffer) next-total-size)))))))


(defn create-wire
  [{:as opts
    :keys [::send|
           ::recv|]}]
  {:pre [(s/assert ::create-wire-opts opts)]
   :post [(s/assert ::wire %)]}
  (let [stateV (volatile!
                {:am-choking? true
                 :am-interested? false
                 :peer-choking? true
                 :peer-interested? false
                 :extensions {}
                 :uploaded 0
                 :downloaded 0
                 :peer-pieces (bytes.core/bitset 0 {:grow (* 50000 8)})})

        msg| (chan 100)
        handshake| (chan 1)

        wire
        ^{:type ::wire}
        (reify
          Wire
          (handshake*
            [_ infohash peer-id])
          #?@(:clj
              [clojure.lang.IDeref
               (deref [_] @stateV)]
              :cljs
              [cljs.core/IDeref
               (-deref [_] @stateV)]))]

    (go
      (loop []
        (when-let [msg (<! msg|)]
          (let [])


          (recur))))

    (let [msg-buffer| (chan 1)
          next-msg-length| (chan 1)]
      
      (go
        (let [handshake| (chan 100)]
          (buffer-messages {::recv|  handshake|
                            ::msg-buffer| msg-buffer|
                            ::next-msg-length| next-msg-length})
          (loop []
            (alt!
              msg-buffer|
              ([buffer]
               
               )

              recv|
              ([buffer]
               (>! handshake|))

              :priority true))
          (close! handshake|))

        (when-let [buffer (<! recv|)]
          (let [pstrlen (bytes.core/get-byte buffer 0)])


          (loop [recv-buffer (transient [])
                 total-size 0
                 expected-size 1]
            (when-let [buffer (<! recv|)]
              (let [msg-length (bytes.core/get-int buffer 0)]
                (condp == msg-length
                  0
                  {:message-key :keep-alive}
                  19
                  {})))))

        (close! msg|)
        (close! handshake|))
      
      )
    
    
    
    

    wire))

(defn create-ut-metadata
  [{:as opts
    :keys []}]
  (let [stateV (volatile!
                {})

        ut-metadata
        ^{:type ::wire}
        (reify
          Extension
          (foo* [_])


          #?@(:clj
              [clojure.lang.IDeref
               (deref [_] @stateV)]
              :cljs
              [cljs.core/IDeref
               (-deref [_] @stateV)]))]

    (go
      (loop []
        (let [])))

    ut-metadata))


(comment

  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.bittorrent/wire {:local/root "./bittorrent/src/wire"}
                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/src/spec"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}
                      github.cljctools/core-jvm {:local/root "./cljctools/src/core-jvm"}}}'
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.bittorrent/wire {:local/root "./bittorrent/src/wire"}
                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/src/spec"}
                      github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}
                      github.cljctools/core-js {:local/root "./cljctools/src/core-js"}}}' \
   -M -m cljs.main --repl-env node --compile cljctools.bittorrent.wire.core --repl
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    
    (require '[cljctools.bytes.core :as bytes.core] :reload)
    (require '[cljctools.bittorrent.wire.core :as wire.core] :reload))
  

   
  
  
  
  ;
  )


(comment

  (time
   (doseq [i (range 10000)
           j (range 10000)]
     (== i j)))
  ; "Elapsed time: 1230.363084 msecs"

  (time
   (doseq [i (range 10000)
           j (range 10000)]
     (= i j)))
  ; "Elapsed time: 3089.990067 msecs"

  ;
  )