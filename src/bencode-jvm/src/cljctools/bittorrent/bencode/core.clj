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

(def ^:const l-int (int \l))
(def ^:const d-int (int \d))
(def ^:const e-int (int \e))
(def ^:const i-int (int \i))
(def ^:const colon-int (int \:))

(defmulti encode*
  (fn [data os]
    (type data)))

(defmethod encode* ::byte-buffer
  [data ^OutputStream os])

(defmethod encode* ::number?
  [num ^OutputStream os]
  (.write os i-int)
  (.write os (-> num (.toString) (.getBytes "UTF-8")))
  (.write os e-int))

(defmethod encode* ::string?
  [string ^OutputStream os]
  (encode* (.getBytes string "UTF-8") os))

(defmethod encode* ::keyword?
  [kword ^OutputStream os]
  (println :aaa)
  (encode* (.getBytes (name kword) "UTF-8") os))

(defmethod encode* ::sequential?
  [coll ^OutputStream os]
  (.write os l-int)
  (doseq [item coll]
    (.write os (encode* item)))
  (.write os e-int))

(defmethod encode* ::map?
  [amap ^OutputStream os]
  (.write os d-int)
  (doseq [[k v] (into (sorted-map) amap)]
    (encode* k os)
    (encode* v os))
  (.write os e-int))

(defmethod encode* ::bytes
  [byte-arr ^OutputStream os]
  (println :bbbs)
  (.write os (-> byte-arr (alength) (Integer/toString) (.getBytes "UTF-8")))
  (.write os colon-int)
  (.write os byte-arr))

(defn encode
  [data]
  (with-open [baos (ByteArrayOutputStream.)]
    (encode* data baos)
    (.close baos)
    (ByteBuffer/wrap (.toByteArray baos))))

(defn decode
  [string])


(comment
  
  clj -Sdeps '{:deps {cljctools.bittorrent/bencode-jvm {:local/root "./bittorrent/src/bencode-jvm"}
                      cljctools.bittorrent/dht-crawl-jvm {:local/root "./bittorrent/src/dht-crawl-jvm"} }}'
  
  (do
    (defn reload
      []
      (require '[cljctools.bittorrent.bencode.core :refer [encode]] :reload)
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
   )
  
  (bencode-encode
   {:t (random-bytes 2)
    :a {:id (random-bytes 20)}}
   )
  
  
  
  ;;
  )


