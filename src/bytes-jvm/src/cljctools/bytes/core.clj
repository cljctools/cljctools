(ns cljctools.bytes.core
  (:refer-clojure :exclude [bytes? bytes])
  (:require
   [cljctools.bytes.protocols :as bytes.protocols])
  (:import
   (java.util Random BitSet)
   (java.io ByteArrayOutputStream ByteArrayInputStream PushbackInputStream Closeable)))

(set! *warn-on-reflection* true)

(defn bytes?
  [x]
  (clojure.core/bytes? x))

(defmulti to-bytes type)

(defmethod to-bytes String ^bytes
  [^String string]
  (.getBytes string "UTF-8"))

(defn size ^Integer
  [^bytes bytes-arr]
  (alength bytes-arr))

(defn to-string ^String
  [^bytes bytes-arr]
  (String. bytes-arr "UTF-8"))

(defn bytes
  [size-or-seq]
  (byte-array size-or-seq))

(deftype TPushbackInputStream [^PushbackInputStream in]
  bytes.protocols/IPushbackInputStream
  (read*
    [_]
    (.read in))
  (read*
    [_  offset length]
    (let [^bytes byte-arr (byte-array ^Integer length)]
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
  (write-bytes*
    [_ byte-arr]
    (.writeBytes out ^bytes byte-arr))
  (reset*
    [_]
    (.reset out))
  bytes.protocols/IToBytes
  (to-bytes*
    [_]
    (.toByteArray out))
  java.io.Closeable
  (close [_] #_(do nil)))

(defn output-stream
  []
  (->
   (ByteArrayOutputStream.)
   (TOutputStream.)))

(defn random-bytes ^bytes
  [^Number length]
  (let [^bytes byte-arr (byte-array length)]
    (.nextBytes (Random.) byte-arr)
    byte-arr))

(deftype TBitSet [^BitSet bitset]
  bytes.protocols/IBitSet
  (get*
    [_ bit-index]
    (.get bitset ^int bit-index))
  (get-subset*
    [_ from-index to-index]
    (.get bitset ^int from-index ^int to-index))
  (set*
    [_ bit-index]
    (.set bitset ^int bit-index))
  (set*
    [_ bit-index value]
    (.set bitset ^int bit-index ^boolean value))
  (size*
    [_]
    (.size bitset))
  bytes.protocols/IToBytes
  (to-bytes*
    [_]
    (.toByteArray bitset))
  bytes.protocols/IToArray
  (to-array*
    [_]
    (.toLongArray bitset)))

(defn bitset
  ([]
   (TBitSet. (BitSet.)))
  ([nbits]
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