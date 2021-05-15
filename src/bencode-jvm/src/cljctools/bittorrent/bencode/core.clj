(ns cljctools.bittorrent.bencode.core
  (:import
   (java.io InputStream OutputStream ByteArrayOutputStream ByteArrayInputStream PushbackInputStream)))

(set! *warn-on-reflection* true)

(derive java.lang.Number ::number?)
(derive java.lang.String ::string?)
(derive clojure.lang.Keyword ::keyword?)
(derive clojure.lang.IPersistentMap ::map?)
(derive clojure.lang.Sequential ::sequential?)
(derive (Class/forName "[B") ::bytes)

(def ^Integer ^:const colon-int (int \:))
(def ^Integer ^:const i-int (int \i))
(def ^Integer ^:const e-int (int \e))
(def ^Integer ^:const l-int (int \l))
(def ^Integer ^:const d-int (int \d))

(defmulti encode*
  (fn [data out]
    (type data)))

(defmethod encode* ::number?
  [^Number num ^OutputStream out]
  (.write out i-int)
  (.write out (-> num (.toString) (.getBytes "UTF-8")))
  (.write out e-int))

(defmethod encode* ::string?
  [^String string ^OutputStream out]
  (encode* (.getBytes string "UTF-8") out))

(defmethod encode* ::keyword?
  [kword ^OutputStream out]
  (encode* (.getBytes (name kword) "UTF-8") out))

(defmethod encode* ::sequential?
  [coll ^OutputStream out]
  (.write out l-int)
  (doseq [item coll]
    (encode* item out))
  (.write out e-int))

(defmethod encode* ::map?
  [amap ^OutputStream out]
  (.write out d-int)
  (doseq [[k v] (into (sorted-map) amap)]
    (encode* k out)
    (encode* v out))
  (.write out e-int))

(defmethod encode* ::bytes
  [^bytes byte-arr ^ByteArrayOutputStream out]
  (.write out (-> byte-arr (alength) (Integer/toString) (.getBytes "UTF-8")))
  (.write out colon-int)
  (.write out byte-arr))

(defn encode
  [data]
  (with-open [out (ByteArrayOutputStream.)]
    (encode* data out)
    (.toByteArray out)))

(defn peek-next
  [^PushbackInputStream in]
  (let [char-int (.read in)]
    (when (= -1 char-int)
      (throw (ex-info (str ::decode* " unexpected end of InputStream") {})))
    (.unread in char-int)
    char-int))

(defmulti decode*
  (fn
    ([in out]
     (condp = (peek-next in)
       i-int :integer
       l-int :list
       d-int :dictionary
       :else :bytes))
    ([in out dispatch-val]
     dispatch-val)))

(defmethod decode* :dictionary
  [^InputStream in
   ^ByteArrayOutputStream out
   & args]
  (.read in) ; skip d char
  (loop [result (transient [])]
    (let [char-int (peek-next in)]
      (cond

        (= char-int e-int) ; return
        (do
          (.reset out)
          (apply hash-map (persistent! result)))

        (= char-int i-int)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got integer") {})
          (recur (conj! result  (decode* in out :integer))))

        (= char-int d-int)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got dictionary") {})
          (recur (conj! result  (decode* in out :dictionary))))

        (= char-int l-int)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got list") {})
          (recur (conj! result  (decode* in out :list))))

        :else
        (let [^bytes bytes-arr (decode* in out :bytes)
              next-element (if (even? (count result))
                             #_its_a_key
                             (String. bytes-arr "UTF-8")
                             #_its_a_value
                             bytes-arr)]
          (recur (conj! result next-element)))))))

(defmethod decode* :list
  [^InputStream in
   ^ByteArrayOutputStream out
   & args]
  (.read in) ; skip l char
  (loop [result (transient [])]
    (let [char-int (peek-next in)]
      (cond

        (= char-int e-int) ; return
        (do
          (.reset out)
          (persistent! result))

        (= char-int i-int)
        (recur (conj! result  (decode* in out :integer)))

        (= char-int d-int)
        (recur (conj! result  (decode* in out :dictionary)))

        (= char-int l-int)
        (recur (conj! result  (decode* in out :list)))

        :else
        (recur (conj! result (decode* in out :bytes)))))))

(defmethod decode* :integer
  [^InputStream in
   ^ByteArrayOutputStream out
   & args]
  (.read in) ; skip i char
  (loop []
    (let [char-int (.read in)]
      (cond

        (= char-int e-int)
        (let [number-string (->
                             (.toByteArray out)
                             (String. "UTF-8"))
              value (try
                      (Integer/parseInt number-string)
                      (catch Exception e
                        (Double/parseDouble number-string)))]
          (.reset out)
          value)

        :else (do
                (.write out char-int)
                (recur))))))

(defmethod decode* :bytes
  [^InputStream in
   ^ByteArrayOutputStream out
   & args]
  (loop []
    (let [char-int (.read in)]
      (cond

        (= char-int colon-int)
        (let [size (-> (.toByteArray out)
                       (String. "UTF-8")
                       (Integer/parseInt))
              byte-arr (byte-array size)]
          (.read in byte-arr 0 size)
          (.reset out)
          byte-arr)

        :else (do
                (.write out char-int)
                (recur))))))

(defn decode
  [^bytes byte-arr]
  (with-open [in (->
                  byte-arr
                  (ByteArrayInputStream.)
                  (PushbackInputStream.))
              out (ByteArrayOutputStream.)]
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