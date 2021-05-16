(ns cljctools.runtime.nio-async.core
  (:import
   (java.util.concurrent Executors Executor ThreadFactory ExecutorService)
   (java.nio ByteBuffer)
   (java.nio.channels SelectionKey Selector)))

(set! *warn-on-reflection* true)

(defonce ^int pool-size 1)
(defonce ^Selector selector (Selector/open))
(defonce ^ExecutorService executor-service (Executors/newFixedThreadPool
                           pool-size
                           (reify
                             ThreadFactory
                             (newThread
                               [_ runnable]
                               (let [t (Thread. ^Runnable runnable)]
                                 (doto t
                                   (.setDaemon true)))))))

(.execute executor-service
          ^Runnable
          (fn []
            (try
              (loop []
                (.select selector)
                (let []))
              (catch Exception ex (do nil)))))
