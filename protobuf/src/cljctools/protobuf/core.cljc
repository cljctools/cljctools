(ns cljctools.protobuf.core
  "should be clojure data -> bytes out -> data, no .proto files, compilers and other crap - just data -> bytes -> data -> bytes"
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.spec :as bytes.spec]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.varint.core :as varint.core]))

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

(defn encode
  [value registry]
  (let [out (bytes.core/byte-array-output-stream)
        value-type (type value)]
    (loop []
      (cond

        (identical? value-type ::varint)
        (bytes.protocols/write-byte-array* out (-> (varint.core/encode-varint value) (bytes.core/to-byte-array)))))))

(defn decode
  [value])


(comment

  (require
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.varint.core :as varint.core]
   '[cljctools.protobuf.core :as protobuf.core]
   :reload)


  (def registry
    {::Record {:key {:field-number 1
                     :wire-type ::byte-array}
               :value {:field-number 2
                       :wire-type ::byte-array}
               :author {:field-number 3
                        :wire-type ::byte-array}
               :signature {:field-number 4
                           :wire-type ::byte-array}
               :timeReceived {:field-number 5
                              :wire-type ::string}}

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
                  :wire-type ::byte-array}
             :addrs {:field-number 2
                     :wire-type ::byte-array
                     :repeated? true}
             :connection {:field-number 3
                          :wire-type ::ConnectionType}}

     ::Message {:type {:field-number 1
                       :wire-type ::MessageType}
                :key {:field-number 2
                      :wire-type ::byte-array}
                :record {:field-number 3
                         :wire-type ::Record}
                :closerPeers {:field-number 8
                              :wire-type ::Peer
                              :repeated? true}
                :providerPeers {:field-number 8
                                :wire-type ::Peer
                                :repeated? true}
                :clusterLevelRaw {:field-number 10
                                  :wire-type ::int32}}})

  (let [msg
        ^{:type ::Message}
        {:type 0
         :key (bytes.core/byte-array 5)
         :record {:key (bytes.core/byte-array 5)
                  :value (bytes.core/byte-array 5)
                  :author (bytes.core/byte-array 5)
                  :signature (bytes.core/byte-array 5)
                  :timeReceived "1970-01-01"}
         :closerPeers [{:id (bytes.core/byte-array 5)
                        :addrs [(bytes.core/byte-array 5)
                                (bytes.core/byte-array 5)]
                        :connection 1}
                       {:id (bytes.core/byte-array 5)
                        :addrs [(bytes.core/byte-array 5)
                                (bytes.core/byte-array 5)]
                        :connection 1}]
         :providerPeers [{:id (bytes.core/byte-array 5)
                          :addrs [(bytes.core/byte-array 5)
                                  (bytes.core/byte-array 5)]
                          :connection 1}
                         {:id (bytes.core/byte-array 5)
                          :addrs [(bytes.core/byte-array 5)
                                  (bytes.core/byte-array 5)]
                          :connection 1}]
         :clusterLevelRaw 123}]
    (->
     (encode msg (merge default-registry
                        registry))))




  ;
  )