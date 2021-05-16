(ns cljctools.bittorrent.bencode.core
  (:require
   [cljctools.runtime.bytes.protocols :as bytes.protocols]
   [cljctools.runtime.bytes.core :as bytes.core]
   [cljctools.runtime.core :as runtime.core]))

(def ^Integer ^:const colon-int8 (runtime.core/char-code \:))
(def ^Integer ^:const i-int8 (runtime.core/char-code \i))
(def ^Integer ^:const e-int8 (runtime.core/char-code \e))
(def ^Integer ^:const l-int8 (runtime.core/char-code \l))
(def ^Integer ^:const d-int8 (runtime.core/char-code \d))

(defmulti encode*
  (fn
    ([data out]
     (cond
       (bytes.core/bytes? data) ::bytes
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
  (bytes.protocols/write-bytes* out (bytes.core/to-bytes (str number)))
  (bytes.protocols/write* out e-int8))

(defmethod encode* ::string
  [string out]
  (encode* (bytes.core/to-bytes string) out))

(defmethod encode* ::keyword
  [kword out]
  (encode* (bytes.core/to-bytes (name kword)) out))

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

(defmethod encode* ::bytes
  [byts out]
  (bytes.protocols/write-bytes* out (-> byts (bytes.core/size) (str) (bytes.core/to-bytes)))
  (bytes.protocols/write* out colon-int8)
  (bytes.protocols/write-bytes* out byts))

(defn encode
  [data]
  (let [out (bytes.core/output-stream)]
    (encode* data out)
    (bytes.protocols/to-bytes* out)))

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
       :else ::bytes))
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
        (let [byts (decode* in out ::bytes)
              next-element (if (even? (count result))
                             #_its_a_key
                             (bytes.core/to-string byts)
                             #_its_a_value
                             byts)]
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
        (recur (conj! result (decode* in out ::bytes)))))))

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
                             (bytes.protocols/to-bytes* out)
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

(defmethod decode* ::bytes
  [in
   out
   & args]
  (loop []
    (let [int8 (bytes.protocols/read* in)]
      (cond

        (= int8 colon-int8)
        (let [length (-> (bytes.protocols/to-bytes* out)
                         (bytes.core/to-string)
                         #?(:clj (Integer/parseInt)
                            :cljs (js/Number.parseInt)))
              byts (bytes.protocols/read* in 0 length)]
          (bytes.protocols/reset* out)
          byts)

        :else (do
                (bytes.protocols/write* out int8)
                (recur))))))

(defn decode
  [byts]
  (let [in (bytes.core/pushback-input-stream byts)
        out (bytes.core/output-stream)]
    (decode* in out)))


(comment

  clj -Sdeps '{:deps {github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.runtime/core-jvm {:local/root "./runtime/src/core-jvm"}
                      github.cljctools.runtime/bytes-jvm {:local/root "./runtime/src/bytes-jvm"}
                      github.cljctools.runtime/codec-jvm {:local/root "./runtime/src/codec-jvm"}}}'

  (do
    (defn reload
      []
      (require '[cljctools.bittorrent.bencode.core :as bencode.core] :reload)
      (require '[cljctools.runtime.bytes.core :as bytes.core] :reload)
      (require '[cljctools.runtime.codec.core :as codec.core] :reload))
    (reload))
  
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.runtime/core-js {:local/root "./runtime/src/core-js"}
                      github.cljctools.runtime/bytes-js {:local/root "./runtime/src/bytes-js"}
                      github.cljctools.runtime/codec-js {:local/root "./runtime/src/codec-js"}}}' \
  -M -m cljs.main --repl-env node --watch "bittorrent/src/bencode" --compile cljctools.bittorrent.bencode.core --repl

  (require '[cljctools.bittorrent.bencode.core :as bencode.core])
  (require '[cljctools.runtime.bytes.core :as bytes.core])
  (require '[cljctools.runtime.codec.core :as codec.core])
  
  (do
    #_(def data {:t "aabbccdd"
                 :a {"id" "197957dab1d2900c5f6d9178656d525e22e63300"}})

    (def data {:t (codec.core/hex-decode "aabbccdd")
               :a {"id" (codec.core/hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")}})

    (->
     (bencode.core/encode data)
     #_(bytes.core/to-string)
     (bencode.core/decode)
     (-> (get-in ["a" "id"]))
     (codec.core/hex-encode-string)))

  ;
  )