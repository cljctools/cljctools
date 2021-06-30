(ns cljctools.bittorrent.dht-crawl.find-nodes
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.codec.runtime.core :as codec.runtime.core]
   [cljctools.bittorrent.dht-crawl.impl :refer [decode-nodes
                                                now]]))

#?(:clj (do (set! *warn-on-reflection* true) (set! *unchecked-math* true)))

(defn start-bootstrap-query
  [{:as opts
    :keys [stateA
           self-idBA
           send-krpc-request
           nodesBA|
           stop|
           nodes-bootstrap]}]

  (go
    (loop [timeout| (timeout 0)]
      (alt!
        timeout|
        ([_]
         (doseq [node nodes-bootstrap]
           (take!
            (send-krpc-request
             {:t (bytes.runtime.core/random-bytes 4)
              :y "q"
              :q "find_node"
              :a {:id self-idBA
                  :target self-idBA #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}}
             node
             (timeout 2000))
            (fn [{:keys [msg] :as value}]
              (when value
                (when-let [nodes (get-in msg [:r :nodes])]
                  (put! nodesBA| nodes)))))

           (doseq [[id routing-table] (:dht-keyspace @stateA)]
             (<! (timeout 500))
             (take!
              (send-krpc-request
               {:t (bytes.runtime.core/random-bytes 4)
                :y "q"
                :q "find_node"
                :a {:id self-idBA
                    :target (codec.runtime.core/hex-to-bytes id)  #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}}
               node
               (timeout 2000))
              (fn [{:keys [msg] :as value}]
                (when value
                  (when-let [nodes (get-in msg [:r :nodes])]
                    (put! nodesBA| nodes)))))))

         (recur (timeout (* 3 60 1000))))

        stop|
        (do :stop)))))


(defn start-dht-query
  [{:as opts
    :keys [stateA
           self-idBA
           send-krpc-request
           nodesBA|
           stop|]}]
  (go
    (loop [timeout| (timeout 1000)]
      (alt!
        timeout|
        ([_]
         (let [state @stateA
               not-find-noded? (fn [[id node]]
                                 (not (get (:routing-table-find-noded state) id)))]
           
           (doseq [[id node] (sequence
                              (comp
                               (filter not-find-noded?)
                               (take 1))
                              (:routing-table state))]
             (swap! stateA update-in [:routing-table-find-noded] assoc id {:node node
                                                                           :timestamp (now)})
             (take!
              (send-krpc-request
               {:t (bytes.runtime.core/random-bytes 4)
                :y "q"
                :q "find_node"
                :a {:id self-idBA
                    :target self-idBA #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}}
               node
               (timeout 2000))
              (fn [{:keys [msg ] :as value}]
                (when value
                  (when-let [nodes (get-in msg [:r :nodes])]
                    (put! nodesBA| nodes))))))

           (doseq [[k routing-table] (:dht-keyspace state)
                   [id node] (->>
                              routing-table
                              (sequence
                               (comp
                                (filter not-find-noded?)
                                (take 1))))]
             (<! (timeout 400))
             (swap! stateA update-in [:routing-table-find-noded] assoc id {:node node
                                                                           :timestamp (now)})
             (take!
              (send-krpc-request
               {:t (bytes.runtime.core/random-bytes 4)
                :y "q"
                :q "find_node"
                :a {:id self-idBA
                    :target (codec.runtime.core/hex-to-bytes k)  #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}}
               node
               (timeout 2000))
              (fn [{:keys [msg] :as value}]
                (when value
                  (when-let [nodes (get-in msg [:r :nodes])]
                    (put! nodesBA| nodes)))))))

         (recur (timeout (* 4 1000))))

        stop|
        (do :stop)))))