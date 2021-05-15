(ns cljctools.bittorrent.bencode.core
  (:require
   [cljctools.runtime.bytes.protocols :as bytes.protocols]
   [cljctools.runtime.bytes.core :as bytes.core]))

(def ^Integer ^:const colon-int (bytes.core/char-code \:))
(def ^Integer ^:const i-int (bytes.core/char-code \i))
(def ^Integer ^:const e-int (bytes.core/char-code \e))
(def ^Integer ^:const l-int (bytes.core/char-code \l))
(def ^Integer ^:const d-int (bytes.core/char-code \d))

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
  (bytes.protocols/write* out i-int)
  (bytes.protocols/write-bytes* out (bytes.core/to-bytes (str number)))
  (bytes.protocols/write* out e-int))

(defmethod encode* ::string
  [string out]
  (encode* (bytes.core/to-bytes string) out))

(defmethod encode* ::keyword
  [kword out]
  (encode* (bytes.core/to-bytes (name kword)) out))

(defmethod encode* ::sequential
  [coll out]
  (bytes.protocols/write* out l-int)
  (doseq [item coll]
    (encode* item out))
  (bytes.protocols/write* out e-int))

(defmethod encode* ::map
  [mp out]
  (bytes.protocols/write* out d-int)
  (doseq [[k v] (into (sorted-map) mp)]
    (encode* k out)
    (encode* v out))
  (bytes.protocols/write* out e-int))

(defmethod encode* ::bytes
  [byts out]
  (bytes.protocols/write-bytes* out (-> byts (bytes.core/size) (str) (bytes.core/to-bytes)))
  (bytes.protocols/write* out colon-int)
  (bytes.protocols/write-bytes* out byts))

(defn encode
  [data]
  (let [out (bytes.core/output-stream)]
    (encode* data out)
    (bytes.protocols/to-bytes* out)))

(defn peek-next
  [in]
  (let [char-int (bytes.protocols/read* in)]
    (when (= -1 char-int)
      (throw (ex-info (str ::decode* " unexpected end of InputStream") {})))
    (bytes.protocols/unread* in char-int)
    char-int))

(defmulti decode*
  (fn
    ([in out]
     (condp = (peek-next in)
       i-int ::integer
       l-int ::list
       d-int ::dictionary
       :else ::bytes))
    ([in out dispatch-val]
     dispatch-val)))

(defmethod decode* ::dictionary
  [in
   out
   & args]
  (bytes.protocols/read* in) ; skip d char
  (loop [result (transient [])]
    (let [char-int (peek-next in)]
      (cond

        (= char-int e-int) ; return
        (do
          (bytes.protocols/reset* out)
          (apply hash-map (persistent! result)))

        (= char-int i-int)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got integer") {})
          (recur (conj! result  (decode* in out ::integer))))

        (= char-int d-int)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got dictionary") {})
          (recur (conj! result  (decode* in out ::dictionary))))

        (= char-int l-int)
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
    (let [char-int (peek-next in)]
      (cond

        (= char-int e-int) ; return
        (do
          (bytes.protocols/reset* out)
          (persistent! result))

        (= char-int i-int)
        (recur (conj! result (decode* in out ::integer)))

        (= char-int d-int)
        (recur (conj! result  (decode* in out ::dictionary)))

        (= char-int l-int)
        (recur (conj! result  (decode* in out ::list)))

        :else
        (recur (conj! result (decode* in out ::bytes)))))))

(defmethod decode* ::integer
  [in
   out
   & args]
  (bytes.protocols/read* in) ; skip i char
  (loop []
    (let [char-int (bytes.protocols/read* in)]
      (cond

        (= char-int e-int)
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
                (bytes.protocols/write* out char-int)
                (recur))))))

(defmethod decode* ::bytes
  [in
   out
   & args]
  (loop []
    (let [char-int (bytes.protocols/read* in)]
      (cond

        (= char-int colon-int)
        (let [length (-> (bytes.protocols/to-bytes* out)
                         (bytes.core/to-string)
                         #?(:clj (Integer/parseInt)
                            :cljs (js/Number.parseInt)))
              byts (bytes.protocols/read* in 0 length)]
          (bytes.protocols/reset* out)
          byts)

        :else (do
                (bytes.protocols/write* out char-int)
                (recur))))))

(defn decode
  [byts]
  (let [in (bytes.core/pushback-input-stream byts)
        out (bytes.core/output-stream)]
    (decode* in out)))


(comment

  clj -Sdeps '{:deps {github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
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