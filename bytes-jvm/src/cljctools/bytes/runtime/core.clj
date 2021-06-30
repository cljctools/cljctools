(ns cljctools.bytes.runtime.core
  (:refer-clojure :exclude [alength byte-array concat aset-byte unchecked-int unchecked-byte])
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.spec :as bytes.spec])
  (:import
   (java.util Random BitSet Arrays)
   (java.nio ByteBuffer)
   (java.io ByteArrayOutputStream ByteArrayInputStream PushbackInputStream Closeable)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

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

(defn copy-byte-array
  [byte-arr from to]
  (Arrays/copyOfRange ^bytes byte-arr ^int from ^int to))

(defmulti equals? (fn [x & more] (type x)) :hierarchy #'types)

(defmethod equals? ::bytes.spec/byte-array ^Boolean
  [byte-arr1 byte-arr2]
  (Arrays/equals ^bytes byte-arr1 ^bytes byte-arr2))

(defmulti to-byte-array (fn [x & more] (type x)) :hierarchy #'types)

(defmethod to-byte-array ::string ^bytes
  [^String string]
  (.getBytes string "UTF-8"))

(defmethod to-byte-array ::bytes.spec/byte-buffer ^bytes
  ([^ByteBuffer buffer]
   (if (and
        (zero? (.position buffer))
        (zero? (.arrayOffset buffer))
        (== (.limit buffer) (.capacity buffer)))
     (.array buffer)
     (to-byte-array buffer (unchecked-add (.arrayOffset buffer) (.position buffer)) (unchecked-add (.arrayOffset buffer) (.limit buffer)) )))
  ([^ByteBuffer buffer from to]
   (copy-byte-array (.array buffer) from to)))

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

(defn position
  ([^ByteBuffer buffer]
   (.position buffer))
  ([^ByteBuffer buffer new-position]
   (.position buffer ^int new-position)))

(defn array-offset
  [^ByteBuffer buffer]
  (.arrayOffset buffer))

(defn limit
  [^ByteBuffer buffer]
  (.limit buffer))

(defn remaining
  [^ByteBuffer buffer]
  (.remaining buffer))

(defn capacity
  [^ByteBuffer buffer]
  (.capacity buffer))

(defn mark
  [^ByteBuffer buffer]
  (.mark buffer))

(defn reset
  [^ByteBuffer buffer]
  (.reset buffer))

(defn rewind
  [^ByteBuffer buffer]
  (.rewind buffer))

(defn buffer-allocate ^ByteBuffer
  [size]
  (ByteBuffer/allocate ^int size))

(defn buffer-wrap
  ([byte-arr]
   (ByteBuffer/wrap ^bytes byte-arr))
  ([byte-arr offset length]
   (ByteBuffer/wrap ^bytes byte-arr ^int offset ^int length)))

(defn buffer-slice
  ([^ByteBuffer buffer]
   (.slice buffer))
  ([^ByteBuffer buffer index length]
   (.slice buffer ^int index ^int length)))

#_(defn unchecked-int
    [x]
    (clojure.core/unchecked-int x))

#_(defn unchecked-short
    [x]
    (clojure.core/unchecked-short x))

#_(defn byte-to-unsigned-int
    [^byte x]
    (java.lang.Byte/toUnsignedInt  x)
    #_(bit-and 0xFF))

#_(defn int-to-unsigned-long
    [x]
    (java.lang.Integer/toUnsignedLong ^int x)
    #_(bit-and 0xffffffff))

(defn unchecked-int
  [x]
  (clojure.core/unchecked-int x))

(defn unchecked-byte
  [x]
  (clojure.core/unchecked-byte x))

(defn double-to-raw-long-bits
  [x]
  (Double/doubleToRawLongBits ^double x))

(defn float-to-raw-int-bits
  [x]
  (Float/floatToRawIntBits ^float x))

#_(defn short-to-unsigned-int
    [^short x]
    (java.lang.Short/toUnsignedInt x)
    #_(bit-and 0xffff))

(defn get-byte
  ([^ByteBuffer buffer]
   (.get buffer))
  ([^ByteBuffer buffer index]
   (.get buffer ^int index)))

(defn get-byte-array
  ([^ByteBuffer buffer byte-arr]
   (.get buffer ^bytes byte-arr))
  ([^ByteBuffer buffer index byte-arr]
   (.get buffer ^int index ^bytes byte-arr))
  ([^ByteBuffer buffer byte-arr offset length]
   (.get buffer ^bytes byte-arr ^int offset ^int length))
  ([^ByteBuffer buffer index byte-arr offset length]
   (.get buffer ^int index ^bytes byte-arr ^int offset ^int length)))

(defn get-uint8
  ([^ByteBuffer buffer]
   (java.lang.Byte/toUnsignedInt ^byte (get-byte buffer)))
  ([^ByteBuffer buffer index]
   (java.lang.Byte/toUnsignedInt ^byte (get-byte buffer index))))

(defn get-int
  ([^ByteBuffer buffer]
   (.getInt buffer))
  ([^ByteBuffer buffer index]
   (.getInt buffer ^int index)))

(defn get-uint32
  ([^ByteBuffer buffer]
   (java.lang.Integer/toUnsignedLong ^int (get-int buffer)))
  ([^ByteBuffer buffer index]
   (java.lang.Integer/toUnsignedLong ^int (get-int buffer index))))

(defn get-short
  ([^ByteBuffer buffer]
   (.getShort buffer))
  ([^ByteBuffer buffer index]
   (.getShort buffer ^int index)))

(defn get-uint16
  ([^ByteBuffer buffer]
   (java.lang.Short/toUnsignedInt ^short (get-short buffer)))
  ([^ByteBuffer buffer index]
   (java.lang.Short/toUnsignedInt ^short (get-short buffer index))))

(defn put-byte
  ([^ByteBuffer buffer value]
   (.put buffer ^byte value))
  ([^ByteBuffer buffer index value]
   (.put buffer ^int index ^byte value)))

(defn put-byte-array
  ([^ByteBuffer buffer byte-arr]
   (.put buffer ^bytes byte-arr))
  ([^ByteBuffer buffer index byte-arr]
   (.put buffer ^int index ^bytes byte-arr))
  ([^ByteBuffer buffer index byte-arr offset length]
   (.put buffer ^int index ^bytes byte-arr ^int offset ^int length)))

(defn put-uint8
  ([^ByteBuffer buffer value]
   (put-byte buffer (clojure.core/unchecked-byte value)))
  ([^ByteBuffer buffer index value]
   (put-byte buffer index (clojure.core/unchecked-byte value))))

(defn put-int
  ([^ByteBuffer buffer value]
   (.putInt buffer ^int value))
  ([^ByteBuffer buffer index value]
   (.putInt buffer ^int index ^int value)))

(defn put-uint32
  ([^ByteBuffer buffer value]
   (put-int buffer (clojure.core/unchecked-int value)))
  ([^ByteBuffer buffer index value]
   (put-int buffer index (clojure.core/unchecked-int value))))

(defn put-short
  ([^ByteBuffer buffer value]
   (.putShort buffer ^short value))
  ([^ByteBuffer buffer index value]
   (.putShort buffer ^int index ^short value)))

(defn put-uint16
  ([^ByteBuffer buffer value]
   (put-short buffer (unchecked-short value)))
  ([^ByteBuffer buffer index value]
   (put-short buffer index (unchecked-short value))))

(defn aset-byte
  [^bytes byte-arr idx val]
  (clojure.core/aset-byte byte-arr ^int idx ^int val))

(defn aset-uint8
  [^bytes byte-arr idx val]
  (clojure.core/aset-byte byte-arr ^int idx (clojure.core/unchecked-byte ^int val)))

(defn aget-byte ^java.lang.Byte
  [^bytes byte-arr idx]
  (clojure.core/aget byte-arr ^int idx))

(deftype TPushbackInputStream [^PushbackInputStream in]
  bytes.protocols/IPushbackInputStream
  (read*
    [_]
    (.read in))
  (read*
    [_ length]
    (let [^bytes byte-arr (clojure.core/byte-array ^int length)]
      (.read in byte-arr (int 0) ^int length)
      byte-arr))
  (unread*
    [_  int8]
    (.unread in ^int int8))
  (unread-byte-array*
    [_  byte-arr]
    (.unread in ^bytes byte-arr))
  (unread-byte-array*
    [_  byte-arr offset length]
    (.unread in ^bytes byte-arr ^int offset ^int length))
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
    (.write out ^int int8))
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
   (java.security.MessageDigest/getInstance "SHA-1")
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
  
  clj -Sdeps '{:deps {github.cljctools/bytes-jvm {:local/root "./cljctools/bytes-jvm"}
                      byte-streams/byte-streams  {:mvn/version "0.2.5-alpha2"}}}'
  
  (do
    (set! *warn-on-reflection* true)
    (require '[cljctools.bytes.runtime.core :as bytes.runtime.core] :reload))

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
  
   clj -Sdeps '{:deps {github.cljctools/bytes-jvm {:local/root "./cljctools/bytes-jvm"}
                       github.cljctools/bytes-meta {:local/root "./cljctools/bytes-meta"}
                       byte-streams/byte-streams {:mvn/version "0.2.5-alpha2"}
                       github.cljctools/codec-jvm {:local/root "./cljctools/codec-jvm"}}}'
  
  (do
    (set! *warn-on-reflection* true)
    (require '[cljctools.bytes.runtime.core :as bytes.runtime.core] :reload)
    (require '[cljctools.codec.core :as codec.core] :reload))
  
  (->
   (java.security.MessageDigest/getInstance "sha1")
   (.digest (bytes.runtime.core/to-byte-array (clojure.string/join "" (repeat 1000 "aabbccdd"))))
   (codec.core/hex-to-string))
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


(comment

  (do
    (set! *warn-on-reflection* true)
    (defn aset-byte1
      [^bytes byte-arr idx val]
      (clojure.core/aset-byte byte-arr ^int idx ^int val))

    (defn aset-byte2
      [^bytes byte-arr idx val]
      (clojure.core/aset-byte byte-arr ^int idx ^byte val))

    (defn aset-byte3
      [^bytes byte-arr idx val]
      (clojure.core/aset-byte byte-arr  (int idx) (byte val)))

    (defn foo
      [aset-byte]
      (let [byte-arr (bytes.runtime.core/byte-array 20)]
        (dotimes [i 10000000]
          (aset-byte byte-arr 5 5))))

    (time (foo aset-byte1))
    (time (foo aset-byte2))
    (time (foo aset-byte3))

    ;
    )

  ;
  )


(comment

  (time
   (dotimes [i 100000000]
     (mod i 20)))
  ; "Elapsed time: 3290.230083 msecs"


  (time
   (dotimes [i 100000000]
     (unchecked-remainder-int i 20)))
  ; "Elapsed time: 162.802177 msecs"


  ;
  )


(comment

  (time
   (let [ba (bytes.runtime.core/byte-array (range 20))]
     (dotimes [i 1000000]
       (-> [(bytes.runtime.core/copy-byte-array ba 0 10)
            (bytes.runtime.core/copy-byte-array ba 10 20)]
           (bytes.runtime.core/concat)))))
  ; "Elapsed time: 382.270465 msecs"

  (time
   (let [ba (bytes.runtime.core/byte-array (range 20))]
     (dotimes [i 1000000]
       (-> [(bytes.runtime.core/buffer-wrap ba 0 10)
            (bytes.runtime.core/buffer-wrap ba 10 10)]
           (bytes.runtime.core/concat)
           (bytes.runtime.core/to-byte-array)))))
  ; "Elapsed time: 1666.753437 msecs"

  ;
  )