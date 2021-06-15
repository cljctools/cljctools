(ns cljctools.protobuf.core
  "should be clojure data -> bytes out -> data, no .proto files, compilers and other crap - just data -> bytes -> data -> bytes"
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.spec :as bytes.spec]
   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.varint.core :as varint.core]))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(defn encode-fixed
  [x baos n]
  (dotimes [i (int n)]
    (bytes.protocols/write* baos (-> (bit-shift-right x (* i 8)) (bytes.runtime.core/unchecked-int) (bit-and 0xff)))))

(defn encode-little-endian32
  [x baos]
  (encode-fixed x baos 4))

(defn encode-little-endian64
  [x baos]
  (encode-fixed x baos 8))

(def default-registry
  {})

(def wire-types
  (->>
   {0 #{::int32 ::int64 ::uint32 ::uint64 ::sint32 ::sint64 ::boolean ::enum}
    1 #{::fixed64 ::sfixed64 ::double}
    2 #{::string ::byte-array ::map ::sequential}
    3 #{:deprecated}
    4 #{:deprecated}
    5 #{::fixed32 ::sfixed32 ::float}}
   (map (fn [[wire-type wire-type-set]]
          (map (fn [value-type] [value-type wire-type])  wire-type-set)))
   (flatten)
   (apply hash-map)))

(defmulti encode*
  (fn
    ([value value-type registry baos]
     (cond
       (bytes.runtime.core/byte-array? value) ::byte-array
       (string? value) ::string
       (map? value) ::map
       (keyword? value) ::enum
       :else value-type))
    ([value value-type registry baos dispatch-val]
     dispatch-val)))

(defmethod encode* ::byte-array
  [value value-type registry baos & more]
  (let [byte-arr-length (bytes.runtime.core/alength value)]
    (varint.core/encode-uint32 byte-arr-length baos)
    (bytes.protocols/write-byte-array* baos value)))

(defmethod encode* ::string
  [value value-type registry baos & more]
  (encode* (bytes.runtime.core/to-byte-array value) value-type registry baos ::byte-array))

(defmethod encode* ::int32
  [value value-type registry baos & more]
  (varint.core/encode-int32 value baos))

(defmethod encode* ::int64
  [value value-type registry baos & more]
  (varint.core/encode-int64 value baos))

(defmethod encode* ::uint32
  [value value-type registry baos & more]
  (varint.core/encode-uint32 value baos))

(defmethod encode* ::uint64
  [value value-type registry baos & more]
  (varint.core/encode-uint64 value baos))

(defmethod encode* ::sint32
  [value value-type registry baos & more]
  (varint.core/encode-sint32 value baos))

(defmethod encode* ::sint64
  [value value-type registry baos & more]
  (varint.core/encode-sint64 value baos))

(defmethod encode* ::boolean
  [value value-type registry baos & more]
  (bytes.protocols/write* baos (if value 1 0)))

(defmethod encode* ::fixed32
  [value value-type registry baos & more]
  (encode-fixed value baos 4))

(defmethod encode* ::fixed64
  [value value-type registry baos & more]
  (encode-fixed value baos 8))

(defmethod encode* ::sfixed32
  [value value-type registry baos & more]
  (encode-fixed value baos 4))

(defmethod encode* ::sfixed64
  [value value-type registry baos & more]
  (encode-fixed value baos 8))

(defmethod encode* ::float
  [value value-type registry baos & more]
  (encode-fixed (bytes.runtime.core/float-to-raw-int-bits value) baos 4))

(defmethod encode* ::double
  [value value-type registry baos & more]
  (encode-fixed (bytes.runtime.core/double-to-raw-long-bits value) baos 8))

(defn encode-tag
  [field-number wire-type baos]
  (->
   (-> (bit-shift-left field-number 3) (bit-or wire-type))
   (varint.core/encode-uint32 baos)))

(defn decode-tag
  [buffer]
  (let [value (varint.core/decode-uint32 buffer)]
    {:field-number (int (bit-shift-right value 3))
     :wire-type  (int (bit-and value 0x07))}))

(defmethod encode* ::enum
  [value value-type registry baos & more]
  (varint.core/encode-int32 (get-in registry [value-type value]) baos))

(defmethod encode* ::map
  [value value-type registry baos & more]
  (let [value-proto (get registry value-type)]
    (doseq [[k k-value] value
            :let [{k-value-type :value-type
                   k-field-number :field-number
                   packed? :packed?} (get value-proto k)
                  k-wire-type (get wire-types k-value-type 2)
                  packed? (or packed? (and (sequential? k-value) (#{0 1 5} k-wire-type)))]]
      (cond
        packed?
        (let [baos-packed (bytes.runtime.core/byte-array-output-stream)]
          (encode-tag k-field-number k-wire-type baos)
          (doseq [seq-value k-value]
            (encode* seq-value k-value-type registry baos-packed))
          (let [packed-byte-arr (bytes.protocols/to-byte-array* baos-packed)]
            (varint.core/encode-uint32 (bytes.runtime.core/alength packed-byte-arr) baos)
            (bytes.protocols/write-byte-array* baos packed-byte-arr)))

        (sequential? k-value)
        (doseq [seq-value k-value]
          (encode-tag k-field-number k-wire-type baos)
          (encode* seq-value k-value-type registry baos))

        (map? k-value)
        (do
          (encode-tag k-field-number k-wire-type baos)
          (let [baos-map (bytes.runtime.core/byte-array-output-stream)]
            (encode* k-value k-value-type registry baos-map)
            (encode* (bytes.protocols/to-byte-array* baos-map) ::byte-array registry baos ::byte-array)))

        :else
        (do
          (encode-tag k-field-number k-wire-type baos)
          (encode* k-value k-value-type registry baos))))))

(defn encode
  [value value-type registry]
  (let [baos (bytes.runtime.core/byte-array-output-stream)]
    (encode* value value-type registry baos)
    (bytes.protocols/to-byte-array* baos)))

(defmulti decode*
  (fn
    ([buffer value-type registry]
     value-type)
    ([buffer value-type registry dispatch-val]
     dispatch-val)))

(defmethod decode* ::byte-array
  [buffer value-type registry & more]
  (bytes.runtime.core/to-byte-array buffer))

(defmethod decode* ::string
  [buffer value-type registry & more]
  (bytes.runtime.core/to-string buffer))

(defmethod decode* ::int32
  [buffer value-type registry & more]
  (varint.core/decode-int32 buffer))

(defmethod decode* ::int64
  [buffer value-type registry & more]
  (varint.core/decode-int64 buffer))

(defmethod decode* :default
  [buffer value-type registry & more]
  (let [value-proto (get registry value-type)]
    (loop [result {}]
      (if (== (bytes.runtime.core/position buffer) (dec (bytes.runtime.core/limit buffer)))
        result
        (let [{:keys [field-number wire-type]} (decode-tag buffer)
              [k {:keys [value-type repeated?]}] (first (filter (fn [[k k-meta]] (== (:field-number k-meta) field-number))  value-proto))
              k-buffer (cond
                         (== wire-type 2)
                         (let [length (varint.core/decode-uint32 buffer)
                               k-buffer (bytes.runtime.core/buffer-slice buffer (+ (bytes.runtime.core/array-offset buffer) (bytes.runtime.core/position buffer)) length)]
                           (bytes.runtime.core/position buffer (+ (bytes.runtime.core/position buffer) length))
                           k-buffer)

                         :else
                         buffer)
              packed? (and repeated? (== wire-type 2))]

          (recur (cond
                   packed?
                   (loop [result result]
                     (if (== (bytes.runtime.core/position buffer) (dec (bytes.runtime.core/limit buffer)))
                       result
                       (recur (update result k conj (decode* k-buffer value-type registry)))))

                   repeated?
                   (update result k conj (decode* k-buffer value-type registry))

                   :else
                   (assoc result k (decode* k-buffer value-type registry)))))))))

(defn decode
  [buffer value-type registry]
  (decode* buffer value-type registry))


(comment

  (require
   '[cljctools.bytes.spec :as bytes.spec]
   '[cljctools.bytes.runtime.core :as bytes.runtime.core]
   '[cljctools.varint.core :as varint.core]
   '[cljctools.protobuf.core :as protobuf.core]
   :reload)


  (def registry
    {::Record {:key {:field-number 1
                     :value-type ::byte-array}
               :value {:field-number 2
                       :value-type ::byte-array}
               :author {:field-number 3
                        :value-type ::byte-array}
               :signature {:field-number 4
                           :value-type ::byte-array}
               :timeReceived {:field-number 5
                              :value-type ::string}}

     ::MessageType {:PUT_VALUE 0
                    :GET_VALUE 1
                    :ADD_PROVIDER 2
                    :GET_PROVIDERS 3
                    :FIND_NODE 4
                    :PING 5}

     ::ConnectionType {:NOT_CONNECTED 0
                       :CONNECTED 1
                       :CAN_CONNECT 2
                       :CANNOT_CONNECT 3}

     ::Peer {:id {:field-number 1
                  :value-type ::byte-array}
             :addrs {:field-number 2
                     :value-type ::byte-array
                     :repeated? true}
             :connection {:field-number 3
                          :value-type ::ConnectionType
                          :enum? true}}

     ::Message {:type {:field-number 1
                       :value-type ::MessageType
                       :enum? true}
                :key {:field-number 2
                      :value-type ::byte-array}
                :record {:field-number 3
                         :value-type ::Record}
                :closerPeers {:field-number 8
                              :value-type ::Peer
                              :repeated? true}
                :providerPeers {:field-number 8
                                :value-type ::Peer
                                :repeated? true}
                :clusterLevelRaw {:field-number 10
                                  :value-type ::int32}}})

  (let [registry (merge default-registry
                        registry)
        msg
        {:type :PUT_VALUE
         :key (bytes.runtime.core/byte-array 5)
         :record {:key (bytes.runtime.core/byte-array 5)
                  :value (bytes.runtime.core/byte-array 5)
                  :author (bytes.runtime.core/byte-array 5)
                  :signature (bytes.runtime.core/byte-array 5)
                  :timeReceived "1970-01-01"}
         :closerPeers [{:id (bytes.runtime.core/byte-array 5)
                        :addrs [(bytes.runtime.core/byte-array 5)
                                (bytes.runtime.core/byte-array 5)]
                        :connection :CONNECTED}
                       {:id (bytes.runtime.core/byte-array 5)
                        :addrs [(bytes.runtime.core/byte-array 5)
                                (bytes.runtime.core/byte-array 5)]
                        :connection :CONNECTED}]
         :providerPeers [{:id (bytes.runtime.core/byte-array 5)
                          :addrs [(bytes.runtime.core/byte-array 5)
                                  (bytes.runtime.core/byte-array 5)]
                          :connection :CONNECTED}
                         {:id (bytes.runtime.core/byte-array 5)
                          :addrs [(bytes.runtime.core/byte-array 5)
                                  (bytes.runtime.core/byte-array 5)]
                          :connection :CONNECTED}]
         :clusterLevelRaw 123}]
    (->
     (encode msg ::Message registry)))




  ;
  )