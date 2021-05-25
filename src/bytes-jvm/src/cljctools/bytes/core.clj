(ns cljctools.bytes.core
  (:refer-clojure :exclude [alength byte-array concat aset-byte])
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.spec :as bytes.spec])
  (:import
   (java.util Random BitSet)
   (java.nio ByteBuffer)
   (java.io ByteArrayOutputStream ByteArrayInputStream PushbackInputStream Closeable)))

(set! *warn-on-reflection* true)

(def ^:const ByteArray (Class/forName "[B")) #_(class (clojure.core/byte-array 0))

(defonce types
  (-> (make-hierarchy)
      (derive java.lang.Number ::number)
      (derive java.lang.String ::string)
      (derive clojure.lang.Keyword ::keyword)
      (derive clojure.lang.IPersistentMap ::map)
      (derive clojure.lang.Sequential ::sequential)
      (derive ByteArray ::bytes.spec/byte-array)
      (derive java.nio.ByteBuffer ::bytes.spec/byte-buffer)))

(defn random-bytes ^bytes
  [^Number length]
  (let [^bytes byte-arr (clojure.core/byte-array length)]
    (.nextBytes (Random.) byte-arr)
    byte-arr))

(defn byte-array?
  [x]
  (clojure.core/bytes? x))

(defmulti to-byte-array type :hierarchy #'types)

(defmethod to-byte-array ::string ^bytes
  [^String string]
  (.getBytes string "UTF-8"))

(defmethod to-byte-array ::bytes.spec/byte-buffer ^bytes
  [^ByteBuffer buffer]
  (if (== (.remaining buffer) (.capacity buffer))
    (.array buffer)
    (let [position (.position buffer)
          ^bytes byte-arr (clojure.core/byte-array (.remaining buffer))]
      (.get buffer byte-arr)
      (.position buffer position)
      byte-arr)))

(defn alength ^Integer
  [^bytes byte-arr]
  (clojure.core/alength byte-arr))

(defmulti to-string type :hierarchy #'types)

(defmethod to-string ::bytes.spec/byte-array ^String
  [^bytes byte-arr]
  (String. byte-arr "UTF-8"))

(defmethod to-string ::bytes.spec/byte-buffer ^String
  [^ByteBuffer buffer]
  (String. ^bytes (to-byte-array buffer) "UTF-8"))

(defmethod to-string ::string ^String
  [^String string]
  string)

(defn byte-array
  [size-or-seq]
  (clojure.core/byte-array size-or-seq))

(defmulti concat
  (fn [xs] (type (first xs))) :hierarchy #'types)

(defmethod concat ::bytes.spec/byte-array ^bytes
  [byte-arrs]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (doseq [^bytes byte-arr byte-arrs]
      (.write out byte-arr))
    (.toByteArray out)))

(defmethod concat ::bytes.spec/byte-buffer ^ByteBuffer
  [buffers]
  (->
   (concat (map #(to-byte-array %) buffers))
   (ByteBuffer/wrap)))

(defmethod concat :default
  [xs]
  xs)

(defn byte-buffer ^ByteBuffer
  [size]
  (ByteBuffer/allocate ^int size))

(defmulti buffer-wrap (fn [x & args] (type x)) :hierarchy #'types)

(defmethod buffer-wrap ::bytes.spec/byte-buffer ^ByteBuffer
  ([^ByteBuffer buffer]
   (ByteBuffer/wrap (.array buffer) ^int (.position buffer) ^int (.remaining buffer)))
  ([^ByteBuffer buffer offset length]
   (ByteBuffer/wrap (.array buffer) ^int (+ (.position buffer) offset) ^int length)))

(defmethod buffer-wrap ::bytes.spec/byte-array ^ByteBuffer
  ([^bytes byte-arr]
   (ByteBuffer/wrap byte-arr))
  ([^bytes byte-arr offset length]
   (ByteBuffer/wrap byte-arr ^int offset ^int length)))

(defn get-byte
  [^ByteBuffer buffer index]
  (->
   (.get buffer ^int (+ (.position buffer) index))
   (java.lang.Byte/toUnsignedInt)
   #_(bit-and 0xFF)))

(defn get-int
  [^ByteBuffer buffer index]
  (->
   (.getInt buffer ^int (+ (.position buffer) index))
   (java.lang.Integer/toUnsignedLong)))

(defn put-int
  [^ByteBuffer buffer index value]
  (.putInt buffer ^int (+ (.position buffer) index) ^int value))

(defn put-short
  [^ByteBuffer buffer index value]
  (.putShort buffer ^int (+ (.position buffer) index) ^short value))

(defn get-short
  [^ByteBuffer buffer index]
  (->
   (.getShort buffer ^int (+ (.position buffer) index))
   (java.lang.Short/toUnsignedInt)))

(defn size
  [^ByteBuffer buffer]
  (.remaining buffer))

(defn aset-byte
  [^bytes byte-arr idx val]
  (clojure.core/aset-byte byte-arr idx val))

(deftype TPushbackInputStream [^PushbackInputStream in]
  bytes.protocols/IPushbackInputStream
  (read*
    [_]
    (.read in))
  (read*
    [_  offset length]
    (let [^bytes byte-arr (clojure.core/byte-array ^Integer length)]
      (.read in byte-arr ^Integer offset ^Integer length)
      byte-arr))
  (unread*
    [_  int8]
    (.unread in ^Integer int8))
  java.io.Closeable
  (close [_] #_(do nil)))

(defn pushback-input-stream
  [^bytes byte-arr]
  (->
   byte-arr
   (ByteArrayInputStream.)
   (PushbackInputStream.)
   (TPushbackInputStream.)))

(deftype TByteArrayOutputStream [^ByteArrayOutputStream out]
  bytes.protocols/IByteArrayOutputStream
  (write*
    [_ int8]
    (.write out ^Integer int8))
  (write-byte-array*
    [_ byte-arr]
    (.writeBytes out ^bytes byte-arr))
  (reset*
    [_]
    (.reset out))
  bytes.protocols/IToByteArray
  (to-byte-array*
    [_]
    (.toByteArray out))
  java.io.Closeable
  (close [_] #_(do nil)))

(defn byte-array-output-stream
  []
  (->
   (ByteArrayOutputStream.)
   (TByteArrayOutputStream.)))



(deftype TBitSet [^BitSet bitset]
  bytes.protocols/IBitSet
  (get*
    [_ bit-index]
    (.get bitset ^int bit-index))
  (get-subset*
    [_ from-index to-index]
    (TBitSet. (.get bitset ^int from-index ^int to-index)))
  (set*
    [_ bit-index]
    (.set bitset ^int bit-index))
  (set*
    [_ bit-index value]
    (.set bitset ^int bit-index ^boolean value))
  bytes.protocols/IToByteArray
  (to-byte-array*
    [_]
    (.toByteArray bitset)))

(defn bitset
  ([]
   (TBitSet. (BitSet.)))
  ([nbits]
   (bitset nbits nil))
  ([nbits opts]
   (TBitSet. (BitSet. nbits))))

(defn sha1
  "takes byte array, returns byte array"
  [^bytes byte-arr]
  (->
   (java.security.MessageDigest/getInstance "sha1")
   (.digest byte-arr)))

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



(comment

  (do
    (defn bar1
      [^Integer num ^java.io.ByteArrayOutputStream out]
      (.write out num))

    (defn bar2
      [num ^java.io.ByteArrayOutputStream out]
      (.write out (int num)))

    (time
     (with-open [out (java.io.ByteArrayOutputStream.)]
       (dotimes [i 100000000]
         (bar1 i out))))

    (time
     (with-open [out (java.io.ByteArrayOutputStream.)]
       (dotimes [i 100000000]
         (bar2 i out)))))

  ; "Elapsed time: 1352.17024 msecs"
  ; "Elapsed time: 1682.777967 msecs"

  ;
  )


(comment
  
  clj -Sdeps '{:deps {github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}
                      byte-streams/byte-streams  {:mvn/version "0.2.5-alpha2"}}}'
  
  (do
    (set! *warn-on-reflection* true)
    (require '[cljctools.bytes.core :as bytes.core] :reload))

  (in-ns 'cljctools.bytes.core)
  
  (do
    (in-ns 'cljctools.bytes.core)
    (def b (bitset 0))
    (bytes.protocols/set* b 3)
    (println (bytes.protocols/to-array* b))

    (bytes.protocols/set* b 10)
    (println (bytes.protocols/to-array* b)))
  
  ;
  )


(comment


  (vec (byte-streams/to-byte-array [(byte-array [1 2 3]) (byte-array [4 5 6])]))

  (defn bb-concat
    [byte-arrs]
    (let [^int size (reduce + 0 (map alength byte-arrs))
          ^java.nio.ByteBuffer bb (java.nio.ByteBuffer/allocate size)]
      (doseq [^bytes byte-arr byte-arrs]
        (.put bb byte-arr))
      (count (.array bb))))

  (time
   (bb-concat (repeatedly 100000 #(random-bytes 100))))
  ; "Elapsed time: 1346.136136 msecs"

  (defn out-concat
    [byte-arrs]
    (with-open [out (java.io.ByteArrayOutputStream.)]
      (doseq [^bytes byte-arr byte-arrs]
        (.write out byte-arr))
      (count (.toByteArray out))))

  (time
   (out-concat (repeatedly 100000 #(random-bytes 100))))
  ; "Elapsed time: 84.665219 msecs"

  (defn streams-concat
    [byte-arrs]
    (count (byte-streams/to-byte-array byte-arrs)))

  (time
   (streams-concat (repeatedly 100000 #(random-bytes 100))))
  ; "Elapsed time: 708.307393 msecs"

  ;
  )


(comment
  
   clj -Sdeps '{:deps {github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}
                       github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}
                       byte-streams/byte-streams {:mvn/version "0.2.5-alpha2"}
                       github.cljctools/codec-jvm {:local/root "./cljctools/src/codec-jvm"}}}'
  
  (do
    (set! *warn-on-reflection* true)
    (require '[cljctools.bytes.core :as bytes.core] :reload)
    (require '[cljctools.codec.core :as codec.core] :reload))
  
  (->
   (java.security.MessageDigest/getInstance "sha1")
   (.digest (bytes.core/to-byte-array (clojure.string/join "" (repeat 1000 "aabbccdd"))))
   (codec.core/hex-encode-string))
  ; "49e4076d086a529baf5d5e62f57bacbd9d4dbe81"
  
  (do
    (def crypto (js/require "crypto"))
    (->
     (.createHash crypto "sha1")
     (.update (js/Buffer.from (clojure.string/join "" (repeat 1000 "aabbccdd")) "utf8"))
     (.digest "hex")))
  ; "49e4076d086a529baf5d5e62f57bacbd9d4dbe81"
  
  
  clj -Sdeps '{:deps {byte-streams/byte-streams {:mvn/version "0.2.5-alpha2"}}}'
  
  (do
    (require '[byte-streams :as bs] :reload))
  
  
  ;
  )