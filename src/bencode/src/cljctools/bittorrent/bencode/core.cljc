(ns cljctools.bittorrent.bencode.core
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.core :as cljctools.core]))

(def ^:const colon-int8 58 #_(cljctools.core/char-code \:))
(def ^:const i-int8 105 #_(cljctools.core/char-code \i))
(def ^:const e-int8 101 #_(cljctools.core/char-code \e))
(def ^:const l-int8 108 #_(cljctools.core/char-code \l))
(def ^:const d-int8 100 #_(cljctools.core/char-code \d))

(defmulti encode*
  (fn
    ([data out]
     (cond
       (bytes.core/byte-array? data) ::byte-array
       (number? data) ::number
       (string? data) ::string
       (keyword? data) ::keyword
       (map? data) ::map
       (sequential? data) ::sequential))
    ([data out dispatch-val]
     dispatch-val)))

(defmethod encode* ::number
  [number out]
  (bytes.protocols/write* out i-int8)
  (bytes.protocols/write-byte-array* out (bytes.core/to-byte-array (str number)))
  (bytes.protocols/write* out e-int8))

(defmethod encode* ::string
  [string out]
  (encode* (bytes.core/to-byte-array string) out))

(defmethod encode* ::keyword
  [kword out]
  (encode* (bytes.core/to-byte-array (name kword)) out))

(defmethod encode* ::sequential
  [coll out]
  (bytes.protocols/write* out l-int8)
  (doseq [item coll]
    (encode* item out))
  (bytes.protocols/write* out e-int8))

(defmethod encode* ::map
  [mp out]
  (bytes.protocols/write* out d-int8)
  (doseq [[k v] (into (sorted-map) mp)]
    (encode* k out)
    (encode* v out))
  (bytes.protocols/write* out e-int8))

(defmethod encode* ::byte-array
  [byte-arr out]
  (bytes.protocols/write-byte-array* out (-> byte-arr (bytes.core/alength) (str) (bytes.core/to-byte-array)))
  (bytes.protocols/write* out colon-int8)
  (bytes.protocols/write-byte-array* out byte-arr))

(defn encode
  "Takes clojure data, returns byte array"
  [data]
  (let [out (bytes.core/byte-array-output-stream)]
    (encode* data out)
    (bytes.protocols/to-byte-array* out)))

(defn peek-next
  [in]
  (let [int8 (bytes.protocols/read* in)]
    (when (= -1 int8)
      (throw (ex-info (str ::decode* " unexpected end of InputStream") {})))
    (bytes.protocols/unread* in int8)
    int8))

(defmulti decode*
  (fn
    ([in out]
     (condp = (peek-next in)
       i-int8 ::integer
       l-int8 ::list
       d-int8 ::dictionary
       :else ::byte-array))
    ([in out dispatch-val]
     dispatch-val)))

(defmethod decode* ::dictionary
  [in
   out
   & args]
  (bytes.protocols/read* in) ; skip d char
  (loop [result (transient [])]
    (let [int8 (peek-next in)]
      (cond

        (= int8 e-int8) ; return
        (do
          (bytes.protocols/reset* out)
          (apply hash-map (persistent! result)))

        (= int8 i-int8)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got integer") {})
          (recur (conj! result  (decode* in out ::integer))))

        (= int8 d-int8)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got dictionary") {})
          (recur (conj! result  (decode* in out ::dictionary))))

        (= int8 l-int8)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got list") {})
          (recur (conj! result  (decode* in out ::list))))

        :else
        (let [byte-arr (decode* in out ::byte-array)
              next-element (if (even? (count result))
                             #_its_a_key
                             (bytes.core/to-string byte-arr)
                             #_its_a_value
                             byte-arr)]
          (recur (conj! result next-element)))))))

(defmethod decode* ::list
  [in
   out
   & args]
  (bytes.protocols/read* in) ; skip l char
  (loop [result (transient [])]
    (let [int8 (peek-next in)]
      (cond

        (= int8 e-int8) ; return
        (do
          (bytes.protocols/reset* out)
          (persistent! result))

        (= int8 i-int8)
        (recur (conj! result (decode* in out ::integer)))

        (= int8 d-int8)
        (recur (conj! result  (decode* in out ::dictionary)))

        (= int8 l-int8)
        (recur (conj! result  (decode* in out ::list)))

        :else
        (recur (conj! result (decode* in out ::byte-array)))))))

(defmethod decode* ::integer
  [in
   out
   & args]
  (bytes.protocols/read* in) ; skip i char
  (loop []
    (let [int8 (bytes.protocols/read* in)]
      (cond

        (= int8 e-int8)
        (let [number-string (->
                             (bytes.protocols/to-byte-array* out)
                             (bytes.core/to-string))
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
                (bytes.protocols/write* out int8)
                (recur))))))

(defmethod decode* ::byte-array
  [in
   out
   & args]
  (loop []
    (let [int8 (bytes.protocols/read* in)]
      (cond

        (= int8 colon-int8)
        (let [length (-> (bytes.protocols/to-byte-array* out)
                         (bytes.core/to-string)
                         #?(:clj (Integer/parseInt)
                            :cljs (js/Number.parseInt)))
              byte-arr (bytes.protocols/read* in 0 length)]
          (bytes.protocols/reset* out)
          byte-arr)

        :else (do
                (bytes.protocols/write* out int8)
                (recur))))))

(defn decode
  "Takes byte array, returns clojure data"
  [byte-arr]
  (let [in (bytes.core/pushback-input-stream byte-arr)
        out (bytes.core/byte-array-output-stream)]
    (decode* in out)))


(comment

  clj -Sdeps '{:deps {github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools/core-jvm {:local/root "./cljctools/src/core-jvm"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}
                      github.cljctools/codec-jvm {:local/root "./cljctools/src/codec-jvm"}}}'

  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools/core-js {:local/root "./cljctools/src/core-js"}
                      github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}
                      github.cljctools/codec-js {:local/root "./cljctools/src/codec-js"}}}' \
  -M -m cljs.main --repl-env node --compile cljctools.bittorrent.bencode.core --repl
  
  (do
    (require '[cljctools.bittorrent.bencode.core :as bencode.core] :reload)
    (require '[cljctools.core :as cljctools.core] :reload)
    (require '[cljctools.bytes.core :as bytes.core] :reload)
    (require '[cljctools.codec.core :as codec.core] :reload))
  
  (let [data
        #_{:t "aabbccdd"
           :a {"id" "197957dab1d2900c5f6d9178656d525e22e63300"}}
        {:t (codec.core/hex-decode "aabbccdd")
         :a {"id" (codec.core/hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")}}]

    (->
     (bencode.core/encode data)
     #_(bytes.core/to-string)
     #_(bytes.core/to-byte-array)
     (bencode.core/decode)
     (-> (get-in ["a" "id"]))
     (codec.core/hex-encode-string)))
  
  (let [data
        {:msg_type 1
         :piece 0
         :total_size 3425}]
    (->
     (bencode.core/encode data)
     (bytes.core/to-string)
     (bytes.core/to-byte-array)
     (bencode.core/decode)
     (clojure.walk/keywordize-keys)))

  ;
  )