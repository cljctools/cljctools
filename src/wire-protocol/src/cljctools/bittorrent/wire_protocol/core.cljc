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

(defprotocol WireProtocol
  (handshake* [_ infohash peer-id]))

(defprotocol Extension
  (foo* [_]))

(s/def ::wire-protocol #(and
                         (satisfies? WireProtocol %)
                         #?(:clj (satisfies? clojure.lang.IDeref %))
                         #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))

(s/def ::recv| ::channel)
(s/def ::send| ::channel)

(s/def ::on-error ifn?)
(s/def ::on-message ifn?)

(s/def ::create-wire-opts
  (s/keys :req [::send|
                ::recv|
                ::on-error
                ::on-message]
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
           ::buffer-messages|
           ::msg-buffer|
           :close?]}]
  (go
    (loop [recv-bufferT (transient [])
           prev-msg-buffer nil
           total-size 0]
      (when-let [{:keys [next-msg-length
                         incomplete?]} (<! buffer-messages|)]
        (when-let [buffer (<! recv|)]
          (let [total-size (if incomplete? (+ total-size  (bytes.core/size prev-msg-buffer)) total-size)
                buffer (if incomplete? (bytes.core/concat [prev-msg-buffer buffer]) buffer)
                next-total-size (+ total-size (bytes.core/size buffer))]
            (cond
              (= next-msg-length :any)
              (let [msg-buffer (bytes.core/concat (persistent! (conj! recv-bufferT buffer)))]
                (>! msg-buffer| msg-buffer)
                (recur (transient []) msg-buffer 0))

              (== next-total-size next-msg-length)
              (let [msg-buffer (bytes.core/concat (persistent! (conj! recv-bufferT buffer)))]
                (>! msg-buffer| msg-buffer)
                (recur (transient []) msg-buffer 0))

              (>= next-total-size next-msg-length)
              (let [over-buffer (bytes.core/concat (persistent! (conj! recv-bufferT buffer)))
                    msg-buffer (bytes.core/buffer-wrap over-buffer 0 next-msg-length)
                    leftover-buffer (bytes.core/buffer-wrap over-buffer next-msg-length (- next-total-size next-msg-length))]
                (>! msg-buffer| msg-buffer)
                (recur (transient [leftover-buffer]) msg-buffer (bytes.core/size leftover-buffer)))

              :else
              (recur (conj! recv-bufferT buffer) prev-msg-buffer next-total-size))))))
    (when close?
      (close! msg-buffer|))))

(defn create-wire-protocol
  [{:as opts
    :keys [::send|
           ::recv|
           ::on-error
           ::on-message]}]
  {:pre [(s/assert ::create-wire-opts opts)]
   :post [(s/assert ::wire-protocol %)]}
  (let [stateV (volatile!
                {:am-choking? true
                 :am-interested? false
                 :peer-choking? true
                 :peer-interested? false
                 :extended? false
                 :dht? false
                 :peer-extensions {}})

        msg| (chan 100)

        wire-protocol
        ^{:type ::wire-protocol}
        (reify
          WireProtocol
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
          (on-message msg)
          (recur))))
    
    (go
      (let [msg-buffer| (chan 1)
            buffer-messages| (chan 1)
            _ (buffer-messages {::recv|  recv|
                                ::msg-buffer| msg-buffer|
                                ::buffer-messages| buffer-messages|})
            _ (>! buffer-messages| {:next-msg-length 1})
            pstrlen (bytes.core/get-byte (<! msg-buffer|) 0)
            _ (>! buffer-messages| {:next-msg-length (+ pstrlen 48)})
            handshakeB (<! msg-buffer|)
            pstr (bytes.core/to-string (bytes.core/buffer-wrap handshakeB 0 pstrlen))
            _ (when-not (= pstr "BitTorrent protocol")
                (on-error (ex-info "Peer's protocol is not 'BitTorrent protocol'"  {:pstr pstr})))
            reservedB (bytes.core/buffer-wrap handshakeB pstrlen 8)
            _ (vswap! stateV merge {:extended? (bit-and (bytes.core/get-byte reservedB 5) 0x10)
                                    :dht? (bit-and (bytes.core/get-byte reservedB 7) 0x01)})
            infohashB (bytes.core/buffer-wrap handshakeB (+ pstrlen 8) 20)
            peer-idB (bytes.core/buffer-wrap handshakeB (+ pstrlen 28) 20)
            _ (>! msg| {:message-key :handshake
                        :peer-idB peer-idB
                        :infohashB infohashB})]
        (close! buffer-messages|))

      (let [msg-buffer| (chan 1)
            buffer-messages| (chan 1)]
        (buffer-messages {::recv|  recv|
                          ::msg-buffer| msg-buffer|
                          ::buffer-messages| buffer-messages|
                          :close? true})
        (>! buffer-messages| {:next-msg-length :any})
        (loop []
          (when-let [buffer (<! msg-buffer|)]
            (let [size (bytes.core/size buffer)]
              (cond

                (and (== size 4) (== 0 (bytes.core/get-int buffer 0)))
                (do
                  (>! msg| {:message-key :keep-alive})
                  (>! buffer-messages| {:next-msg-length :any}))

                (<= size 4)
                (do
                  (>! buffer-messages| {:next-msg-length :any :incomplete? true}))


                :else
                (let [msg-length (bytes.core/get-int buffer 0)
                      msg-id (bytes.core/get-byte buffer 4)]
                  (cond

                    (not (== msg-length (- (bytes.core/size buffer) 4)))
                    (do
                      (println [::incomplete
                                [:msg-id msg-id]
                                [:expected-length msg-length :received-length (- (bytes.core/size buffer) 4)]])
                      (>! buffer-messages| {:next-msg-length :any :incomplete? true}))

                    (and (== msg-length 1) (== msg-id 0))
                    (>! msg| {:message-key :choke})

                    (and (== msg-length 1) (== msg-id 1))
                    (>! msg| {:message-key :choke})

                    (and (== msg-length 1) (== msg-id 2))
                    (>! msg| {:message-key :interested})

                    (and (== msg-length 1) (== msg-id 3))
                    (>! msg| {:message-key :not-interested})

                    (and (== msg-length 5) (== msg-id 4))
                    (>! msg| {:message-key :have
                              :piece-index (bytes.core/get-int buffer 5)})

                    (and (== msg-id 5))
                    (>! msg| {:message-key :bitfield})

                    (and (== msg-length 13) (== msg-id 6))
                    (let [index (bytes.core/get-int buffer 5)
                          begin (bytes.core/get-int buffer 9)
                          length (bytes.core/get-int buffer 13)]
                      (>! msg| {:message-key :request
                                :index index
                                :begin begin
                                :length length}))

                    (and  (== msg-id 7))
                    (let [index (bytes.core/get-int buffer 5)
                          begin (bytes.core/get-int buffer 9)
                          block (bytes.core/buffer-wrap (bytes.core/to-byte-array buffer) 13 (- msg-length 9))]
                      (>! msg| {:message-key :piece
                                :index index
                                :begin begin
                                :block block}))

                    (and (== msg-length 13) (== msg-id 8))
                    (>! msg| {:message-key :cancel})

                    (and (== msg-length 3) (== msg-id 9))
                    (>! msg| {:message-key :port})

                    (and (== msg-id 20))
                    (let [ext-msg-id (bytes.core/get-byte buffer 5)
                          dataB (bytes.core/buffer-wrap buffer 6 (- msg-length 2))
                          data (bencode.core/decode (bytes.core/to-byte-array dataB))]
                      (cond
                        (== ext-msg-id 0)
                        (let []
                          (vswap! stateV assoc :peer-extensions data)
                          (>! msg| {:message-key :extended-handshake
                                    :data data}))))))))
            (recur))))
      (close! msg|))

    wire-protocol))

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
                      github.cljctools.bittorrent/wire {:local/root "./bittorrent/src/wire-protocol"}
                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/src/spec"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}
                      github.cljctools/core-jvm {:local/root "./cljctools/src/core-jvm"}}}'
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.bittorrent/wire {:local/root "./bittorrent/src/wire-protocol"}
                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/src/spec"}
                      github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}
                      github.cljctools/core-js {:local/root "./cljctools/src/core-js"}}}' \
   -M -m cljs.main --repl-env node --compile cljctools.bittorrent.wire-protocol.core --repl
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    
    (require '[cljctools.bytes.core :as bytes.core] :reload)
    (require '[cljctools.bittorrent.wire-protocol.core :as wire-protocol.core] :reload))
  ;
  )

(comment


  (bytes.core/get-int (bytes.core/buffer-wrap (bytes.core/byte-array [0 0 0 5])) 0)
  (bytes.core/get-int (bytes.core/buffer-wrap (bytes.core/byte-array [0 0 1 3])) 0)


  ; The bit selected for the extension protocol is bit 20 from the right (counting starts at 0) . 
  ; So (reserved_byte [5] & 0x10) is the expression to use for checking if the client supports extended messaging
  (bit-and 2r00010000  0x10)
  ; => 16


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