(ns cljctools.bytes.core
  (:refer-clojure :exclude [alength byte-array concat])
  (:require
   [cljctools.bytes.protocols :as bytes.protocols])
  (:import
   (java.util Random BitSet)
   (java.nio ByteBuffer)
   (java.io ByteArrayOutputStream ByteArrayInputStream PushbackInputStream Closeable)))

(set! *warn-on-reflection* true)

(defonce types
  (-> (make-hierarchy)
      (derive java.lang.Number ::number)
      (derive java.lang.String ::string)
      (derive clojure.lang.Keyword ::keyword)
      (derive clojure.lang.IPersistentMap ::map)
      (derive clojure.lang.Sequential ::sequential)
      (derive (Class/forName "[B") ::byte-array)
      (derive java.nio.ByteBuffer ::byte-buffer)))

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

(defmethod to-byte-array ::byte-buffer ^bytes
  [^ByteBuffer buffer]
  (.array buffer))

(defn alength ^Integer
  [^bytes byte-arr]
  (clojure.core/alength byte-arr))

(defmulti to-string type :hierarchy #'types)

(defmethod to-string ::byte-array ^String
  [^bytes byte-arr]
  (String. byte-arr "UTF-8"))

(defn byte-array
  [size-or-seq]
  (clojure.core/byte-array size-or-seq))

(defmulti concat
  (fn [xs] (type (first xs))) :hierarchy #'types)

(defmethod concat ::byte-array ^bytes
  [byte-arrs]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (doseq [^bytes byte-arr byte-arrs]
      (.write out byte-arr))
    (.toByteArray out)))

(defmethod concat :default
  [xs]
  xs)

(defn byte-buffer ^ByteBuffer
  [size]
  (ByteBuffer/allocate ^int size))

(defn buffer-wrap ^ByteBuffer
  ([^bytes byte-arr]
   (ByteBuffer/wrap byte-arr))
  ([^bytes byte-arr offset length]
   (ByteBuffer/wrap byte-arr ^int offset ^int length)))

(defn get-byte
  [^ByteBuffer buffer index]
  (.get buffer ^int index))

(defn get-int 
  [^ByteBuffer buffer index]
  (.getInt buffer ^int index))

(defn size
  [^ByteBuffer buffer]
  (.capacity buffer))

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

(deftype TOutputStream [^ByteArrayOutputStream out]
  bytes.protocols/IOutputStream
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

(defn output-stream
  []
  (->
   (ByteArrayOutputStream.)
   (TOutputStream.)))



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