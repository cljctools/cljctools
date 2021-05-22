(ns cljctools.bytes.spec
  (:require
   [clojure.spec.alpha :as s])
  #?(:clj
     (:import
      (java.nio ByteBuffer))))

(s/def ::byte-array #?(:clj bytes?
                       :cljs #(instance? js/Buffer %)))

(s/def ::byte-buffer #?(:clj #(instance? java.nio.ByteBuffer %)
                        :cljs #(instance? js/Buffer %)))