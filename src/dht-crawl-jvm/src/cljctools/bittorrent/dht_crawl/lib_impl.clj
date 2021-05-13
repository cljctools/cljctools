(ns cljctools.bittorrent.dht-crawl.lib-impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cognitect.transit :as transit]
   [bencode.core])
  (:import
   (org.apache.commons.codec.binary Hex)
   (java.util Random)
   (java.io ByteArrayOutputStream ByteArrayInputStream InputStream OutputStream PushbackInputStream)))

(defn random-bytes
  [n]
  (let [byte-arr (byte-array n)]
    (.nextBytes (Random.) byte-arr)
    byte-arr))

(defn hex-decode
  [string]
  (Hex/decodeHex string))

(defn hex-encode-string
  [byte-arr]
  (Hex/encodeHexString byte-arr))

(defn bencode-encode
  [data]
  (-> (doto (ByteArrayOutputStream.)
        (bencode.core/write-bencode data))
      (.toString)))

(defn bencode-decode
  [string]
  (-> (.getBytes string "UTF-8")
      ByteArrayInputStream.
      PushbackInputStream.
      bencode.core/read-bencode))


(comment

  (do
    (require '[bencode.core :refer [read-bencode write-bencode]])
    (import (java.io ByteArrayOutputStream ByteArrayInputStream InputStream OutputStream PushbackInputStream))
    (import (org.apache.commons.codec.binary Hex))
    (import (java.util Random)))

  (do
    (def id (random-bytes 20))
    (time (dotimes [i 10000]
            (hex-encode-string (random-bytes2 20))))

    (time (dotimes [i 10000]
            (hex-encode-string (random-bytes 20)))))

  (let [data {:t (random-bytes 4)
              :a {:id (random-bytes 20)}}
        bencoded (bencode-encode data)]
    bencoded)

  (let [t (random-bytes 4)
        data {:t t
              :a {:id (random-bytes 20)}}
        bencoded (bencode-encode data)
        decoded (bencode-decode bencoded)]
    [decoded
     (hex-encode-string t)
     (hex-encode-string (get decoded "t"))])

  (type (get (bencode-decode "d1:ad2:id20:abcdefghij0123456789e1:q4:ping1:t2:aa1:y1:qe") "t"))

  (-> (random-bytes 4) class .getComponentType (= Byte/TYPE))

  (->
   (bencode-decode "d1:ad2:id20:abcdefghij0123456789e1:q4:ping1:t2:aa1:y1:qe")
   (bencode-encode))

  (->
   (bencode-decode "d1:ad2:id20:d05352b32fff78741f60d43dfd29de0b3bb50973:q4:ping1:t2:aa1:y1:qe")
   (bencode-encode))

  (->
   (bencode-encode {:t "aa"
                    :a {:id "d05352b32fff78741f60d43dfd29de0b3bb50973"}})
   (bencode-decode)
   (bencode-encode))

  (->
   (bencode-encode {:t "aa"
                    :a {:id (random-bytes 20)}})
   #_(bencode-decode)
   #_(bencode-encode))


  (import (com.dampcake.bencode Bencode))

  (let [bencode (Bencode.)]
    (->
     (.encode bencode (java.util.HashMap. {:t "aa"
                                           :a {:id (random-bytes 20)}}))
     (String. (.getCharset bencode))))


  ;
  )