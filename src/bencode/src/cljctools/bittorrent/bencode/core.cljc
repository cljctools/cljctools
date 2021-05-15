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
  (bytes.protocols/write* out (bytes.core/to-bytes (str number)))
  (bytes.protocols/write* out e-int))

(defmethod encode* ::string?
  [string out]
  (encode* (bytes.core/to-bytes string) out))

(defmethod encode* ::keyword?
  [kword out]
  (encode* (bytes.core/to-bytes (name kword)) out))

(defmethod encode* ::sequential?
  [coll out]
  (bytes.protocols/write* out l-int)
  (doseq [item coll]
    (encode* item out))
  (bytes.protocols/write* out e-int))

(defmethod encode* ::map?
  [mp out]
  (bytes.protocols/write* out d-int)
  (doseq [[k v] (into (sorted-map) mp)]
    (encode* k out)
    (encode* v out))
  (bytes.protocols/write* out e-int))

(defmethod encode* ::bytes
  [byts out]
  (bytes.protocols/write* out (-> byts (bytes.core/size) (str) (bytes.core/to-bytes)))
  (bytes.protocols/write* out colon-int)
  (bytes.protocols/write* out byts))

(defn encode
  [data]
  (with-open [out (bytes.core/output-stream)]
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
                             (to-string byts)
                             #_its_a_value
                             byts)]
          (recur (conj! result next-element)))))))

(defmethod decode* :list
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

(defmethod decode* :integer
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

(defmethod decode* :bytes
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
              byts (bytes.core/read* in 0 length)]
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

  clj -Sdeps '{:deps {cljctools.bittorrent/bencode-jvm {:local/root "./bittorrent/src/bencode-jvm"}
                      cljctools.bittorrent/dht-crawl-jvm {:local/root "./bittorrent/src/dht-crawl-jvm"}}}'

  (do
    (defn reload
      []
      (require '[cljctools.bittorrent.bencode.core :refer [encode decode]] :reload)
      (require '[cljctools.bittorrent.dht-crawl.lib-impl :refer [random-bytes
                                                                 hex-decode
                                                                 hex-encode-string
                                                                 bencode-encode
                                                                 bencode-decode]] :reload))
    (reload))


  (->
   (encode {:t (random-bytes 2)
            :a {:id (random-bytes 20)}})
   (.array)
   (String.)
   (decode))

  (bencode-encode
   {:t "aa"
    :a {"foo" 123
        :id "197957dab1d2900c5f6d9178656d525e22e63300"}})

  (->
   (encode {:t (random-bytes 2)
            :a {:id (random-bytes 20)}})
   (.array)
   (String.)
   (decode))


  (=
   (->
    (encode {:t "aa"
             :a {"foo" 123
                 "id" "197957dab1d2900c5f6d9178656d525e22e63300"}})
    (.array)
    (String.))
   (bencode-encode
    {:t "aa"
     :a {"foo" 123
         "id" "197957dab1d2900c5f6d9178656d525e22e63300"}}))

  (let [data {:t (hex-decode "aabbccdd")
              :a {"id" (hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")}}]
    (=
     (bencode-encode data)
     (->
      (encode data)
      (.array)
      (String.))))

  (let [data {:t (hex-decode "aabbccdd")
              :a {"id" (hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")}}]
    (->
     (encode data)
     (.array)
     (String. "UTF-8")
     #_(decode)))

  (count (String. (hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")))

  (let [string (String. (random-bytes 20))]
    (prn string)
    (count string))

  (let [ba (byte-array 4)]
    (prn (vec ba))
    (aset-byte ba 3 50)
    (prn (vec ba))
    [(String. ba)
     (count ba)])

  (let [data {:t (hex-decode "aabbccdd")
              :a {"id" (hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")}}]
    (->
     (encode data)
     (decode)
     (get-in ["a" "id"])
     (hex-encode-string)))

  (let [data {:t (hex-decode "aabbccdd")
              :a {"id" (hex-decode "197957dab1d2900c5f6d9178656d525e22e63300")}}]
    (->
     (encode data)
     (String. "UTF-8")))

  ;
  )

(comment

  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}}} '-M -m cljs.main --repl-env node

  (do
    (def bencode (js/require "bencode"))
    (def crypto (js/require "crypto")))

  (let [data (clj->js {:t (js/Buffer.from "aabbccdd" "hex")
                       :a {"id" (js/Buffer.from "197957dab1d2900c5f6d9178656d525e22e63300" "hex")}})]
    (->
     (.encode bencode data)
     #_(.toString)
     #_(js/Buffer.from)
     (->> (.decode bencode))
     (js->clj)
     (-> (get-in ["a" "id"]))
     (.toString "hex")))
  
  ;
  )


(comment
  
  (do
    (def in (java.io.ByteArrayInputStream. (.getBytes "1123:abcd" "UTF-8")))
    (def out (java.io.ByteArrayOutputStream.))
    (dotimes [_ 4]
      (.write out (.read in)))

    (def bb (java.nio.ByteBuffer/wrap (.toByteArray out)))
    (Integer/parseInt (String. (.toByteArray out) "UTF-8")))
  ;
  )

(comment

  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      cljctools.bittorrent/bencode-js {:local/root "./bittorrent/src/bencode-js"}}} '\
  -M -m cljs.main --repl-env node --watch "bittorrent/src/bencode-js" --compile cljctools.bittorrent.bencode.core --repl

  (require '[cljctools.bittorrent.bencode.core :refer [encode decode]])

  (let [data {:t (js/Buffer.from "aabbccdd" "hex")
              :a {"id" (js/Buffer.from "197957dab1d2900c5f6d9178656d525e22e63300" "hex")}}]
    (->
     (encode data)
     #_(.toString)
     (decode)
     (-> (get-in ["a" "id"]))
     (.toString "hex")))


  ;
  )


(comment

  (do
    (set! *warn-on-reflection* true)
    (defprotocol IFoo
      (do-stuff* [_ a]))

    (deftype Foo [^java.util.LinkedList llist]
      IFoo
      (do-stuff*
        [_ a]
        (dotimes [n a]
          (.add llist n))
        (reduce + 0 llist)))

    (defn foo-d
      []
      (Foo. (java.util.LinkedList.)))

    (defn foo-r
      []
      (let [^java.util.LinkedList llist (java.util.LinkedList.)]
        (reify
          IFoo
          (do-stuff*
            [_ a]
            (dotimes [n a]
              (.add llist n))
            (reduce + 0 llist)))))

    (defn mem
      []
      (->
       (- (-> (Runtime/getRuntime) (.totalMemory)) (-> (Runtime/getRuntime) (.freeMemory)))
       (/ (* 1024 1024))
       (int)
       (str "mb")))

    [(mem)
     (time
      (->>
       (map (fn [i]
              (let [^Foo x (foo-d)]
                (do-stuff* x 10))) (range 0 1000000))
       (reduce + 0)))

     #_(time
        (->>
         (map (fn [i]
                (let [x (foo-r)]
                  (do-stuff* x 10))) (range 0 1000000))
         (reduce + 0)))
     (mem)])

  ; deftype 
  ; "Elapsed time: 348.17539 msecs"
  ; ["10mb" 45000000 "55mb"]

  ; reify
  ; "Elapsed time: 355.863333 msecs"
  ; ["10mb" 45000000 "62mb"]






  (let [llist (java.util.LinkedList.)]
    (dotimes [n 10]
      (.add llist n))
    (reduce + 0 llist))
  ;
  )