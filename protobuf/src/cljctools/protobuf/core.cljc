(ns cljctools.protobuf.core
  "should be clojure data -> bytes out -> data, no .proto files, compilers and other crap - just data -> bytes -> data -> bytes"
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.spec :as bytes.spec]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.varint.core :as varint.core]))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn write-fixed
  [x baos n]
  (dotimes [i (int n)]
    (bytes.protocols/write* baos (-> (bit-shift-right x (* i 8)) (bytes.core/unchecked-int) (bit-and 0xff)))))

(defn write-fixed32
  [x baos]
  (write-fixed x baos 4))

(defn write-fixed64
  [x baos]
  (write-fixed x baos 8))

(defn write-little-endian32
  [x baos]
  (write-fixed32 x baos))

(defn write-little-endian64
  [x baos]
  (write-fixed64 x baos))

(defn write-sfixed32
  [x baos]
  (write-fixed32 x baos))

(defn write-sfixed64
  [x baos]
  (write-fixed64 x baos))

(defn write-double
  [x baos]
  (write-fixed64 (bytes.core/double-to-raw-long-bits x) baos))

(defn write-float
  [x baos]
  (write-fixed32 (bytes.core/float-to-raw-int-bits x) baos))

(defn write-enum
  [x baos]
  (varint.core/encode-int32 x baos))

(defn write-boolean
  [value baos]
  (bytes.protocols/write* baos (if value 1 0)))

(def default-registry
  {::varint number?
   ::string string?
   ::byte-array ::bytes.spec/byte-array
   ::int32 int?
   ::int64 int?
   ::sint32 int?
   ::sint64 int?
   ::uint32 int?
   ::uint64 number?})

(def wire-types
  (->>
   {0 #{::int32 ::int64 ::uint32 ::uint64 ::sint32 ::sint64 ::boolean ::enum}
    1 #{::fixed64 ::sfixed64 ::double}
    2 #{::string ::bytes.spec/byte-array ::map ::sequential}
    3 #{:deprecated}
    4 #{:deprecated}
    5 #{::fixed32 ::sfixed32 ::float}}
   (map (fn [[wire-type wire-type-set]]
          (map (fn [value-type] [value-type wire-type])  wire-type-set)))
   (flatten)
   (apply hash-map)))

(defmulti encode*
  (fn
    ([value-type value registry baos]
     (cond
       (bytes.core/byte-array? value) ::bytes.spec/byte-array
       (number? value) ::number
       (string? value) ::string
       (keyword? value) ::keyword
       (map? value) ::map
       (sequential? value) ::sequential))
    ([value-type value registry baos dispatch-val]
     dispatch-val)))

(defmethod encode* ::bytes.spec/byte-array
  [value-type value registry baos & more]
  (bytes.protocols/write-byte-array* baos (-> (varint.core/encode-varint value) (bytes.core/to-byte-array))))

(defmethod encode* ::string
  [value-type value registry baos & more]
  (let [byte-arr (bytes.core/to-byte-array value)
        byte-arr-length (bytes.core/alength byte-arr)]
    (bytes.protocols/write-byte-array* baos (-> byte-arr-length (varint.core/encode-varint) (bytes.core/to-byte-array)))
    (bytes.protocols/write-byte-array* baos byte-arr)))

(defn encode-key
  [field-number wire-type baos]
  (->>
   (-> (bit-shift-left field-number 3) (bit-or wire-type))
   (varint.core/encode-varint)
   (bytes.core/to-byte-array)
   (bytes.protocols/write-byte-array* baos)))

(defmethod encode* ::map
  [value-type value registry baos & more]
  (let [value-proto (get registry value-type)
        resultT (transient [])]
    (doseq [[k k-value] value
            :let [{:keys [value-type field-number]} (get value-proto k)]]
      (encode-key field-number (get wire-types value-type) baos)
      (encode* value-type k-value registry baos))))

(defn encode
  [value-type value registry]
  (let [baos (bytes.core/byte-array-output-stream)]
    (encode* value-type value registry baos)
    (bytes.protocols/to-byte-array* baos)))

(defn decode
  [value])


(comment

  (require
   '[cljctools.bytes.spec :as bytes.spec]
   '[cljctools.bytes.core :as bytes.core]
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
         :key (bytes.core/byte-array 5)
         :record {:key (bytes.core/byte-array 5)
                  :value (bytes.core/byte-array 5)
                  :author (bytes.core/byte-array 5)
                  :signature (bytes.core/byte-array 5)
                  :timeReceived "1970-01-01"}
         :closerPeers [{:id (bytes.core/byte-array 5)
                        :addrs [(bytes.core/byte-array 5)
                                (bytes.core/byte-array 5)]
                        :connection :CONNECTED}
                       {:id (bytes.core/byte-array 5)
                        :addrs [(bytes.core/byte-array 5)
                                (bytes.core/byte-array 5)]
                        :connection :CONNECTED}]
         :providerPeers [{:id (bytes.core/byte-array 5)
                          :addrs [(bytes.core/byte-array 5)
                                  (bytes.core/byte-array 5)]
                          :connection :CONNECTED}
                         {:id (bytes.core/byte-array 5)
                          :addrs [(bytes.core/byte-array 5)
                                  (bytes.core/byte-array 5)]
                          :connection :CONNECTED}]
         :clusterLevelRaw 123}]
    (->
     (encode ::Message msg registry)))




  ;
  )