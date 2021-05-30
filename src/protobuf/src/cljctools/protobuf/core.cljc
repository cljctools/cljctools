(ns cljctools.protobuf.core
  "should be clojure data -> bytes out -> data, no .proto files, compilers and other crap - just data -> bytes -> data -> bytes"
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]))







(comment

  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}}}'
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                       org.clojure/core.async {:mvn/version "1.3.618"}
                       github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}
                       github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}}}' \
  -M -m cljs.main \
  -co '{:npm-deps {}
        :install-deps true
        :analyze-path "./bittorrent/src/dht-crawl"
        :repl-requires [[cljs.repl :refer-macros [source doc find-doc apropos dir pst]]
                        [cljs.pprint :refer [pprint] :refer-macros [pp]]]} '\
  -ro '{:host "0.0.0.0"
        :port 8899} '\
  --repl-env node --compile cljctools.protobuf.core --repl
  
  (require
   '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                      pub sub unsub mult tap untap mix admix unmix pipe
                                      timeout to-chan  sliding-buffer dropping-buffer
                                      pipeline pipeline-async]]
   '[clojure.core.async.impl.protocols :refer [closed?]])

  (require
   '[cljctools.protobuf.core :as protobuf.core]
   '[cljctools.bytes.core :as bytes.core]
   :reload-all)
  
  (require
   '[cljctools.protobuf.core :as protobuf.core]
   '[cljctools.bytes.core :as bytes.core]
   :reload)


  ;
  )


(comment
  
  
  
  
  ;
  )