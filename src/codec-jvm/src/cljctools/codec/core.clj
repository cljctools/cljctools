(ns cljctools.codec.core
  (:import
   (io.netty.buffer ByteBufUtil)
   #_(org.apache.commons.codec.binary Hex)))

(set! *warn-on-reflection* true)

(defn hex-decode ^bytes
  [^String string]
  (ByteBufUtil/decodeHexDump string)
  #_(Hex/decodeHex string))

(defn hex-encode-string ^String
  [^bytes byte-arr]
  (ByteBufUtil/hexDump byte-arr)
  #_(Hex/encodeHexString byte-arr))

(comment
  
  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/codec-jvm {:local/root "./cljctools/src/codec-jvm"}
                      github.cljctools/bytes {:local/root "./cljctools/src/bytes-jvm"}
                      io.netty/netty-buffer {:mvn/version "4.1.51.Final"}}}'
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    (require '[cljctools.codec.core :as codec.core])
    (require '[cljctools.bytes.core :as bytes.core])
    (import (io.netty.buffer ByteBufUtil)))
  
  
  
  (let [hex (ByteBufUtil/hexDump (bytes.core/random-bytes 20))]
    (= hex
       (ByteBufUtil/hexDump (ByteBufUtil/decodeHexDump hex))))
  
  ;
  )