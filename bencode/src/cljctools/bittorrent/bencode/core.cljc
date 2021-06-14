(ns cljctools.bittorrent.bencode.core
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.impl :as bytes.impl]
   [cljctools.impl :as cljctools.impl]))

(def ^:const colon-byte 58 #_(cljctools.impl/char-code \:))
(def ^:const i-byte 105 #_(cljctools.impl/char-code \i))
(def ^:const e-byte 101 #_(cljctools.impl/char-code \e))
(def ^:const l-byte 108 #_(cljctools.impl/char-code \l))
(def ^:const d-byte 100 #_(cljctools.impl/char-code \d))

(defmulti encode*
  (fn
    ([data out]
     (cond
       (bytes.impl/byte-array? data) ::byte-array
       (number? data) ::number
       (string? data) ::string
       (keyword? data) ::keyword
       (map? data) ::map
       (sequential? data) ::sequential))
    ([data out dispatch-val]
     dispatch-val)))

(defmethod encode* ::number
  [number out]
  (bytes.protocols/write* out i-byte)
  (bytes.protocols/write-byte-array* out (bytes.impl/to-byte-array (str number)))
  (bytes.protocols/write* out e-byte))

(defmethod encode* ::string
  [string out]
  (encode* (bytes.impl/to-byte-array string) out))

(defmethod encode* ::keyword
  [kword out]
  (encode* (bytes.impl/to-byte-array (name kword)) out))

(defmethod encode* ::sequential
  [coll out]
  (bytes.protocols/write* out l-byte)
  (doseq [item coll]
    (encode* item out))
  (bytes.protocols/write* out e-byte))

(defmethod encode* ::map
  [mp out]
  (bytes.protocols/write* out d-byte)
  (doseq [[k v] (into (sorted-map) mp)]
    (encode* k out)
    (encode* v out))
  (bytes.protocols/write* out e-byte))

(defmethod encode* ::byte-array
  [byte-arr out]
  (bytes.protocols/write-byte-array* out (-> byte-arr (bytes.impl/alength) (str) (bytes.impl/to-byte-array)))
  (bytes.protocols/write* out colon-byte)
  (bytes.protocols/write-byte-array* out byte-arr))

(defn encode
  "Takes clojure data, returns byte array"
  [data]
  (let [out (bytes.impl/byte-array-output-stream)]
    (encode* data out)
    (bytes.protocols/to-byte-array* out)))

(defn peek-next
  [in]
  (let [byte (bytes.protocols/read* in)]
    (when (== -1 byte)
      (throw (ex-info (str ::decode* " unexpected end of InputStream") {})))
    (bytes.protocols/unread* in byte)
    byte))

(defmulti decode*
  (fn
    ([in out]
     (condp = (peek-next in)
       i-byte ::integer
       l-byte ::list
       d-byte ::dictionary
       :else ::byte-array))
    ([in out dispatch-val]
     dispatch-val)))

(defmethod decode* ::dictionary
  [in
   out
   & args]
  (bytes.protocols/read* in) ; skip d char
  (loop [result (transient [])]
    (let [byte (peek-next in)]
      (cond

        (== byte e-byte) ; return
        (do
          (bytes.protocols/read* in) ; skip e char
          (bytes.protocols/reset* out)
          (apply hash-map (persistent! result)))

        (== byte i-byte)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got integer") {})
          (recur (conj! result  (decode* in out ::integer))))

        (== byte d-byte)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got dictionary") {})
          (recur (conj! result  (decode* in out ::dictionary))))

        (== byte l-byte)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got list") {})
          (recur (conj! result  (decode* in out ::list))))

        :else
        (let [byte-arr (decode* in out ::byte-array)
              next-element (if (even? (count result))
                             #_its_a_key
                             (bytes.impl/to-string byte-arr)
                             #_its_a_value
                             byte-arr)]
          (recur (conj! result next-element)))))))

(defmethod decode* ::list
  [in
   out
   & args]
  (bytes.protocols/read* in) ; skip l char
  (loop [result (transient [])]
    (let [byte (peek-next in)]
      (cond

        (== byte e-byte) ; return
        (do
          (bytes.protocols/read* in) ; skip e char
          (bytes.protocols/reset* out)
          (persistent! result))

        (== byte i-byte)
        (recur (conj! result (decode* in out ::integer)))

        (== byte d-byte)
        (recur (conj! result  (decode* in out ::dictionary)))

        (== byte l-byte)
        (recur (conj! result  (decode* in out ::list)))

        :else
        (recur (conj! result (decode* in out ::byte-array)))))))

(defmethod decode* ::integer
  [in
   out
   & args]
  (bytes.protocols/read* in) ; skip i char
  (loop []
    (let [byte (bytes.protocols/read* in)]
      (cond

        (== byte e-byte)
        (let [number-string (->
                             (bytes.protocols/to-byte-array* out)
                             (bytes.impl/to-string))
              number (try
                       #?(:clj (Integer/parseInt number-string)
                          :cljs (js/Number.parseInt number-string))
                       (catch
                        #?(:clj Exception
                           :cljs js/Error)
                        error
                         #?(:clj (Double/parseDouble number-string)
                            :cljs (js/Number.parseFloat number-string))))]
          (bytes.protocols/reset* out)
          number)

        :else (do
                (bytes.protocols/write* out byte)
                (recur))))))

(defmethod decode* ::byte-array
  [in
   out
   & args]
  (loop []
    (let [byte (bytes.protocols/read* in)]
      (cond

        (== byte colon-byte)
        (let [length (-> (bytes.protocols/to-byte-array* out)
                         (bytes.impl/to-string)
                         #?(:clj (Integer/parseInt)
                            :cljs (js/Number.parseInt)))
              byte-arr (bytes.protocols/read* in length)]
          (bytes.protocols/reset* out)
          byte-arr)

        :else (do
                (bytes.protocols/write* out byte)
                (recur))))))

(defn decode
  "Takes byte array, returns clojure data"
  [byte-arr]
  (let [in (bytes.impl/pushback-input-stream byte-arr)
        out (bytes.impl/byte-array-output-stream)]
    (decode* in out)))


(comment

  clj -Sdeps '{:deps {github.cljctools.bittorrent/bencode {:local/root "./bittorrent/bencode"}
                      github.cljctools/core-jvm {:local/root "./cljctools/core-jvm"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/bytes-jvm"}
                      github.cljctools/codec-jvm {:local/root "./cljctools/codec-jvm"}}}'

  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/bencode"}
                      github.cljctools/core-js {:local/root "./cljctools/core-js"}
                      github.cljctools/bytes-js {:local/root "./cljctools/bytes-js"}
                      github.cljctools/codec-js {:local/root "./cljctools/codec-js"}}}' \
  -M -m cljs.main --repl-env node --compile cljctools.bittorrent.bencode.core --repl
  
  (require
   '[cljctools.bittorrent.bencode.core :as bencode.core]
   '[cljctools.impl :as cljctools.impl]
   '[cljctools.bytes.impl :as bytes.impl]
   '[cljctools.codec.impl :as codec.impl]
   :reload #_:reload-all)
  
  (let [data
        {:t "aabbccdd"
         :a {"id" "197957dab1d2900c5f6d9178656d525e22e63300"}}
        #_{:t (codec.impl/hex-decode "aabbccdd")
           :a {"id" (codec.impl/hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")}}]

    (->
     (bencode.core/encode data)
     #_(bytes.impl/to-string)
     #_(bytes.impl/to-byte-array)
     (bencode.core/decode)
     #_(-> (get-in ["a" "id"]))
     #_(codec.impl/hex-encode-string)))
  
  (let [data
        {:msg_type 1
         :piece 0
         :total_size 3425}]
    (->
     (bencode.core/encode data)
     (bytes.impl/to-string)
     (bytes.impl/to-byte-array)
     (bencode.core/decode)
     (clojure.walk/keywordize-keys)))

  ;
  )


(comment

  (require
   '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                      pub sub unsub mult tap untap mix admix unmix pipe
                                      timeout to-chan  sliding-buffer dropping-buffer
                                      pipeline pipeline-async]]
   '[clojure.core.async.impl.protocols :refer [closed?]])

  (require
   '[cljctools.bittorrent.bencode.core :as bencode.core]
   '[cljctools.impl :as cljctools.impl]
   '[cljctools.bytes.impl :as bytes.impl]
   '[cljctools.codec.impl :as codec.impl]
   :reload #_:reload-all)

  (defn foo
    [encode decode data]
    (let [timeout| (timeout 20000)]
      (go
        (loop []
          (when-not (closed? timeout|)
            (<! (timeout 10))
            (let [data data]
              (dotimes [i 50]
                (->
                 (encode data)
                 (decode)
                 (encode)
                 (decode))))
            (recur)))
        (println :done))))

  (let [data {:t (codec.impl/hex-decode "aabbccdd")
              :a {"id" (codec.impl/hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")}}]
    (foo bencode.core/encode bencode.core/decode data))

  ; ~ 50% cpu node
  ; ~ 22% cpu jvm

  (def bencode (js/require "bencode"))
  
  (let [data {:t (js/Buffer.from "aabbccdd" "hex") 
              :a {"id" (js/Buffer.from "197957dab1d2900c5f6d9178656d525e22e63300" "hex")}}]
    (foo bencode.encode bencode.decode data))

  ; ~50% cpu


  ;
  )