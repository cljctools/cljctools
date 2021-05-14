(ns cljctools.bittorrent.bencode.core
  (:import
   (java.io ByteArrayOutputStream ByteArrayInputStream InputStream OutputStream PushbackInputStream)
   (java.nio ByteBuffer)
   (java.nio.charset StandardCharsets)))

(derive java.nio.ByteBuffer ::byte-buffer)
(derive java.lang.Number ::number?)
(derive java.lang.String ::string?)
(derive clojure.lang.Keyword ::keyword?)
(derive clojure.lang.IPersistentMap ::map?)
(derive clojure.lang.Sequential ::sequential?)
(derive (Class/forName "[B") ::bytes)

(def ^:const colon-int (int \:))
(def ^:const i-int (int \i))
(def ^:const e-int (int \e))
(def ^:const l-int (int \l))
(def ^:const d-int (int \d))

(defmulti encode*
  (fn [data out]
    (type data)))

(defmethod encode* ::byte-buffer
  [data ^OutputStream out])

(defmethod encode* ::number?
  [num ^OutputStream out]
  (.write out i-int)
  (.write out (-> num (.toString) (.getBytes "UTF-8")))
  (.write out e-int))

(defmethod encode* ::string?
  [string ^OutputStream out]
  (encode* (.getBytes string "UTF-8") out))

(defmethod encode* ::keyword?
  [kword ^OutputStream out]
  (encode* (.getBytes (name kword) "UTF-8") out))

(defmethod encode* ::sequential?
  [coll ^OutputStream out]
  (.write out l-int)
  (doseq [item coll]
    (.write out (encode* item)))
  (.write out e-int))

(defmethod encode* ::map?
  [amap ^OutputStream out]
  (.write out d-int)
  (doseq [[k v] (into (sorted-map) amap)]
    (encode* k out)
    (encode* v out))
  (.write out e-int))

(defmethod encode* ::bytes
  [byte-arr ^OutputStream out]
  (.write out (-> byte-arr (alength) (Integer/toString) (.getBytes "UTF-8")))
  (.write out colon-int)
  (.write out byte-arr))

(defn encode
  [data]
  (with-open [out (ByteArrayOutputStream.)]
    (encode* data out)
    (.close out)
    (ByteBuffer/wrap (.toByteArray out))))

(defn peek-next
  [in]
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
  [in out & args]
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
        (let [next-map-element-byte-arr (decode* in out :bytes)
              next-element (if (even? (count result))
                             #_its_a_key
                             (String. next-map-element-byte-arr "UTF-8")
                             #_its_a_value
                             next-map-element-byte-arr)]
          (recur (conj! result next-element)))))))

(defmethod decode* :list
  [in out & args]
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
  [in out & args]
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
  [in out & args]
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
  [string]
  (with-open [in (->
                  (.getBytes string "UTF-8")
                  (ByteArrayInputStream.)
                  (PushbackInputStream.))
              out (ByteArrayOutputStream.)]
    (decode* in out)))


(comment
  
  clj -Sdeps '{:deps {cljctools.bittorrent/bencode-jvm {:local/root "./bittorrent/src/bencode-jvm"}
                      cljctools.bittorrent/dht-crawl-jvm {:local/root "./bittorrent/src/dht-crawl-jvm"} }}'
  
  (do
    (defn reload
      []
      (require '[cljctools.bittorrent.bencode.core :refer [encode decode]] :reload)
      (require '[cljctools.bittorrent.dht-crawl.lib-impl :refer [random-bytes
                                                                 hex-decode
                                                                 hex-encode-string
                                                                 bencode-encode
                                                                 bencode-decode]] :reload))
    (reload)
    )
  
  
  (->
   (encode {:t (random-bytes 2)
            :a {:id (random-bytes 20)}})
   (.array)
   (String.)
   #_(decode)
   )
  
  (bencode-encode
   {:t (random-bytes 2)
    :a {:id (random-bytes 20)}}
   )
  
  
  
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


