(ns cljctools.nio-async.core
  (:import
   (java.util.concurrent Executors Executor ThreadFactory ExecutorService ConcurrentHashMap)
   (java.nio ByteBuffer)
   (java.nio.channels SelectableChannel SelectionKey Selector SocketChannel ServerSocketChannel)
   (java.util Iterator Set)
   (java.util.function Predicate)))

(set! *warn-on-reflection* true)

(defonce ^int pool-size 1)
(defonce ^Selector selector (Selector/open))
(defonce ^Set selected-keys (.selectedKeys selector))
(defonce ^ConcurrentHashMap channels (ConcurrentHashMap.))
(def ^Predicate should-be-removed? (reify
                                     Predicate
                                     (test
                                       [_ ^SelectableChannel channel]
                                       (not (.isOpen channel)))))

(defonce ^ExecutorService executor-service (Executors/newFixedThreadPool
                           pool-size
                           (reify
                             ThreadFactory
                             (newThread
                               [_ runnable]
                               (let [t (Thread. ^Runnable runnable)]
                                 (doto t
                                   (.setDaemon true)))))))

(defn register
  [^SelectableChannel channel opts]
  (.put channels channel opts))

(defn on-accept
  [^SelectionKey selected-key]
  (let [^ServerSocketChannel server-socket-channel (.channel selected-key)
        opts (.get channels server-socket-channel)
        ^SocketChannel socket-channel (.accept server-socket-channel)]
    ((:on-accept opts) socket-channel)))

(defn on-read
  [^SelectionKey selected-key]
  (let [^SocketChannel socket-channel (.channel selected-key)
        opts (.get channels socket-channel)]
    ((:on-data opts) data)))

(defn on-write
  [^SelectionKey selected-key]
  (let [^ServerSocketChannel server-socket-channel (.channel selected-key)
        opts (.get channels server-socket-channel)]))

(.execute executor-service
          ^Runnable
          (fn []
            (try
              (loop []
                (.select selector)
                (doseq [^SelectionKey selected-key (iterator-seq (.iterator selected-keys))]
                  (.remove selected-keys selected-key)
                  (try
                    (when (.isValid selected-key)
                      (cond
                        (.isAcceptable selected-key)
                        (on-accept selected-key)

                        (.isReadable selected-key)
                        (on-read selected-key)

                        (.isWritable selected-key)
                        (on-write selected-key)))
                    (catch Exceptio ex
                      (throw ex))))
                (.. channels (keySet) (removeIf should-be-removed?)))
              (catch Exception ex (do nil)))))



(comment

  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools/nio-async {:local/root "./cljctools/nio-async"}}}'
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    (require '[cljctools.nio-async.core])
    (import (java.nio ByteBuffer))
    (import (java.nio.channels SelectionKey Selector SocketChannel ServerSocketChannel)))
  
  (do
    
    
    
    )
  

  ;
  )