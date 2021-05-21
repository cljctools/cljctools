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
  (handshake* [_]))

(s/def ::wire-protocol #(and
                         (satisfies? WireProtocol %)
                         #?(:clj (satisfies? clojure.lang.IDeref %))
                         #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))

(s/def ::recv| ::channel)
(s/def ::send| ::channel)
(s/def ::ex| ::channel)


(s/def ::on-error ifn?)
(s/def ::on-message ifn?)
(s/def ::initiate-handshake? boolean?)


(s/def ::create-wire-opts
  (s/keys :req [::send|
                ::recv|
                ::on-error
                ::on-message]
          :opt [::ex|
                ::initiate-handshake?]))



(defprotocol MessageCut
  (concut* [_ buffer])
  (cut* [_ buffer expected-length])
  (add* [_ buffer] [_ buffer next-total-size]))

(defn message-cut
  []
  (let [buffersV (volatile! (transient []))
        prev-resultBV (volatile! nil)
        total-sizeV (volatile! 0)]
    (reify
      MessageCut
      (concut*
        [_ buffer]
        (let [resultB (->
                       @buffersV
                       (conj! buffer)
                       (persistent!)
                       (bytes.core/concat))]
          (vreset! buffersV (transient []))
          (vreset! total-sizeV 0)
          (vreset! prev-resultBV nil)
          resultB))
      (add*
        [t buffer]
        (add* t buffer (+ @total-sizeV (bytes.core/size buffer))))
      (add*
        [_ buffer next-total-size]
        (vswap! buffersV conj! buffer)
        (vreset! total-sizeV  next-total-size)
        nil)
      (cut*
        [t buffer expected-length]
        (cond
          (and (not expected-length) (== 0 (count @buffersV)))
          buffer

          (not expected-length)
          (concut* t buffer)

          :else
          (let [next-total-size (+ @total-sizeV (bytes.core/size buffer))]
            (cond
              (== expected-length next-total-size)
              (concut* t buffer)

              (>= next-total-size expected-length)
              (let [overB (bytes.core/concat (persistent! (conj! @buffersV buffer)))
                    resultB (bytes.core/buffer-wrap overB 0 expected-length)
                    leftoverB (bytes.core/buffer-wrap overB expected-length (- next-total-size expected-length))]
                (vreset! buffersV (transient [leftoverB]))
                resultB)

              :else
              (add* t buffer next-total-size))))))))

(def pstrB (-> (bytes.core/byte-array [19]) (bytes.core/buffer-wrap)))
(def protocolB (-> (bytes.core/to-byte-array "\u0013BitTorrent protocol") (bytes.core/buffer-wrap)))
(def reservedB (-> (bytes.core/byte-array [0 0 0 0 0 2r00010000 0 2r00000001]) (bytes.core/buffer-wrap)))
(def keep-alive-byte-arr (bytes.core/byte-array [0 0 0 0]))
(def choke-byte-arr (bytes.core/byte-array [0 0 0 1 0]))
(def unchoke-byte-arr (bytes.core/byte-array [0 0 0 1 1]))
(def interested-byte-arr (bytes.core/byte-array [0 0 0 1 2]))
(def not-interested-byte-arr (bytes.core/byte-array [0 0 0 1 3]))
(def have-byte-arr (bytes.core/byte-array [0 0 0 5 4]))
(def port-byte-arr (bytes.core/byte-array [0 0 0 3 9 0 0]))

(defn extended-message
  [ext-msg-id data]
  (let [payloadBA (->
                   data
                   (bencode.core/encode))
        msg-lengthB (bytes.core/byte-buffer 4)
        msg-length (+ 2 (bytes.core/alength payloadBA))]
    (bytes.core/put-int msg-lengthB 0 msg-length)
    (->
     (bytes.core/concat
      [(bytes.core/to-byte-array msg-lengthB)
       (bytes.core/byte-array [20 ext-msg-id])
       payloadBA])
     (bytes.core/buffer-wrap))))

(defn handshake-message
  [infohashB peer-idB]
  (bytes.core/concat [pstrB protocolB reservedB infohashB peer-idB]))

(defn extended-handshake-message
  []
  (extended-message 0 {:m {"ut_metadata" 3}
                       :metadata_size 0}))

(defn receive-handshake
  [recv|]
  (go
    ))

(defn create-wire-protocol
  [{:as opts
    :keys [::send|
           ::recv|
           ::on-error
           ::on-message
           ::infohashB
           ::peer-idB]}]
  {:pre [(s/assert ::create-wire-opts opts)]
   :post [(s/assert ::wire-protocol %)]}
  (let [stateV (atom
                {:am-choking? true
                 :am-interested? false
                 :peer-choking? true
                 :peer-interested? false
                 :peer-extended? false
                 :peer-dht? false
                 :peer-extended-payload nil})

        msg| (chan 100)
        ex| (chan 1)
        op| (chan 100)

        wire-protocol
        ^{:type ::wire-protocol}
        (reify
          WireProtocol
          (handshake*
            [_]
            (let [out| (chan 1)]
              (put! op| {:op :handshake :out| out|})
              out|))

          #?@(:clj
              [clojure.lang.IDeref
               (deref [_] @stateV)]
              :cljs
              [cljs.core/IDeref
               (-deref [_] @stateV)]))

        release (fn []
                  (close! msg|))]

    (take! ex|
           (fn [ex]
             (release)
             (when-let [ex| (::ex| opts)]
               (put! ex| ex))))

    (go
      (try
        (>! send| (handshake-message infohashB peer-idB))

        (let [cut (message-cut)
              pstrlenV (volatile! nil)
              expected-lengthV (volatile! nil)]
          (loop []
            (when-let [buffer (<! recv|)]
              (when-let [handshakeB (cut* cut buffer @expected-lengthV)]
                (when-not @expected-lengthV
                  (let [pstrlen (bytes.core/get-byte handshakeB 0)]
                    (vreset! pstrlenV pstrlen)
                    (vreset! expected-lengthV (+ 49 pstrlen))))
                (cond
                  (== (bytes.core/size handshakeB) @expected-lengthV)
                  (let [pstrlen @pstrlenV
                        pstr (bytes.core/to-string (bytes.core/buffer-wrap handshakeB 0 pstrlen))]
                    (if-not (= pstr "BitTorrent protocol")
                      (throw (ex-info "Peer's protocol is not 'BitTorrent protocol'"  {:pstr pstr} nil))
                      (let [reservedB (bytes.core/buffer-wrap handshakeB pstrlen 8)
                            infohashB (bytes.core/buffer-wrap handshakeB (+ pstrlen 8) 20)
                            peer-idB (bytes.core/buffer-wrap handshakeB (+ pstrlen 28) 20)]
                        (vswap! stateV merge {:peer-extended? (bit-and (bytes.core/get-byte reservedB 5) 2r00010000)
                                              :peer-dht? (bit-and (bytes.core/get-byte reservedB 7) 2r00000001)}))))
                  :else
                  (let []
                    (add* cut buffer)
                    (recur)))))))
        (>! send| (extended-handshake-message))

        (let [cut (message-cut)
              expected-lengthV (volatile! nil)]
          (loop []
            (when-let [buffer (<! recv|)]
              (when-let [msgB (cut* cut buffer @expected-lengthV)]
                (let [size (bytes.core/size buffer)]
                  (cond

                    (<= size 4)
                    (do nil :message-incomplete :recur)

                    (and (== size 4) (== 0 (bytes.core/get-int buffer 0)))
                    (>! msg| {:message-key :keep-alive})

                    :else
                    (let [msg-length (bytes.core/get-int buffer 0)
                          msg-id (bytes.core/get-byte buffer 4)]
                      (cond

                        (not (== msg-length (- (bytes.core/size buffer) 4)))
                        (println [::incomplete
                                  [:msg-id msg-id]
                                  [:expected-length msg-length :received-length (- (bytes.core/size buffer) 4)]])

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
                              (>! msg| {:message-key :extended-handshake
                                        :data data})))))))))
              (recur))))
        (catch #?(:clj Exception :cljs :default) ex (put! ex| ex)))
      (release))

    (go
      (loop []
        (when-let [msg (<! msg|)]

          (condp = (:message-key msg)

            :extended-handshake
            (let [{:keys [data]} msg
                  ut-metadata-id (get-in data ["m" "ut_metadata"])]
              (vswap! stateV assoc :peer-extended-payload data)
              (when ut-metadata-id
                (let [metadata-size (get-in data ["metadata_size"])]
                  (>! send| (extended-message ut-metadata-id {:msg_type 0
                                                              :piece 0})))))
            nil)

          (on-message msg)
          (recur))))

    wire-protocol))

(defn request-metadata
  []
  
  )


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

  (->
   (bytes.core/byte-buffer 4)
   (bytes.core/put-int 0 16384)
   (bytes.core/get-int 0))

  (let [byte-buf  (bytes.core/byte-buffer 4)
        _ (bytes.core/put-int byte-buf 0 16384)
        byte-arr (bytes.core/to-byte-array byte-buf)]
    [(bytes.core/alength byte-arr)
     (-> byte-arr
         (bytes.core/buffer-wrap)
         (bytes.core/get-int 0))])


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


(comment

  (do
    (time
     (let [kword :foo/bar]
       (dotimes [i 100000000]
         (= kword :foo/bar))))
    ; "Elapsed time: 191.077891 msecs"

    (time
     (let [kword :foo/bar]
       (dotimes [i 100000000]
         (identical? kword :foo/bar))))
    ; "Elapsed time: 96.919884 msecs"
    )


  ;
  )


(comment

  (do
    (time
     (let [x (atom (transient []))]
       (dotimes [i 10000000]
         (swap! x conj! i))
       (count (persistent! @x))))
    ;"Elapsed time: 684.808948 msecs"

    (time
     (let [x (volatile! (transient []))]
       (dotimes [i 10000000]
         (vswap! x conj! i))
       (count (persistent! @x))))
    ; "Elapsed time: 582.699983 msecs"

    (time
     (let [x (atom [])]
       (dotimes [i 10000000]
         (swap! x conj i))
       (count @x)))
    ; "Elapsed time: 1014.411053 msecs"

    (time
     (let [x (volatile! [])]
       (dotimes [i 10000000]
         (vswap! x conj i))
       (count @x)))
    ; "Elapsed time: 665.942603 msecs"
    )





  ;
  )