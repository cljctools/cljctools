(ns cljctools.runtime.bytes
  (:refer-clojure :exclude [bytes? bytes])
  (:require
   [cljctools.runtime.bytes.protocols :as bytes.protocols])
  (:import
   (java.io ByteArrayOutputStream ByteArrayInputStream PushbackInputStream Closable)))

(set! *warn-on-reflection* true)

(defn bytes?
  [x]
  (clojure.core/bytes? x))

(defn char-code
  [^Character chr]
  (int chr))

(defmulti to-bytes type)

(defmethod to-bytes String
  [^String string]
  (.getBytes string "UTF-8"))

(defn size
  [^bytes bytes-arr]
  (alength bytes-arr))

(defn to-string
  [^bytes bytes-arr]
  (String. bytes-arr "UTF-8"))

(defn bytes
  [^Number length]
  (byte-array length))

(deftype TPushbackInputStream [^PushbackInputStream in]
  bytes.protocols/IPushbackInputStream
  (read*
    [_]
    (.read in))
  (read*
   [_ ^Number offset ^Number length]
   (let [^bytes byte-arr (byte-array length)]
     (.read in byte-arr offset length)
     byte-arr))
  (unread*
   [_ ^Number char-int]
   (.unread in))
  java.io.Closable
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
    [_ data]
    (.write out data))
  (reset*
    [_]
    (.reset out))
  (to-buffer*
    [_]
    (.toByteArray out))
  java.io.Closable
  (close [_] #_(do nil)))

(defn output-stream
  []
  (->
   (ByteArrayOutputStream.)
   (TOutputStream.)))


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