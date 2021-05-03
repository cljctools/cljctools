(ns find.bittorrent.find-nodes
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.core.async.impl.protocols :refer [closed?]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]
   [find.bittorrent.core :refer [decode-nodes]]))

(defonce crypto (js/require "crypto"))

(defn start-bootstrap-query
  [{:as opts
    :keys [stateA
           self-idB
           send-krpc-request
           socket
           nodesB|
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
             socket
             (clj->js
              {:t (.randomBytes crypto 4)
               :y "q"
               :q "find_node"
               :a {:id self-idB
                   :target self-idB #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}})
             (clj->js node)
             (timeout 2000))
            (fn [{:keys [msg rinfo] :as value}]
              (when value
                (when-let [nodes (goog.object/getValueByKeys msg "r" "nodes")]
                  (put! nodesB| nodes)))))

           (doseq [[id routing-table] (:dht-keyspace @stateA)]
             (<! (timeout 500))
             (take!
              (send-krpc-request
               socket
               (clj->js
                {:t (.randomBytes crypto 4)
                 :y "q"
                 :q "find_node"
                 :a {:id self-idB
                     :target (js/Buffer.from id "hex")  #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}})
               (clj->js node)
               (timeout 2000))
              (fn [{:keys [msg rinfo] :as value}]
                (when value
                  (when-let [nodes (goog.object/getValueByKeys msg "r" "nodes")]
                    (put! nodesB| nodes)))))))

         (recur (timeout (* 3 60 1000))))

        stop|
        (do :stop)))))


(defn start-dht-query
  [{:as opts
    :keys [stateA
           self-idB
           send-krpc-request
           socket
           nodesB|
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
                                                                           :timestamp (js/Date.now)})
             (take!
              (send-krpc-request
               socket
               (clj->js
                {:t (.randomBytes crypto 4)
                 :y "q"
                 :q "find_node"
                 :a {:id self-idB
                     :target self-idB #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}})
               (clj->js node)
               (timeout 2000))
              (fn [{:keys [msg rinfo] :as value}]
                (when value
                  (when-let [nodes (goog.object/getValueByKeys msg "r" "nodes")]
                    (put! nodesB| nodes))))))

           (doseq [[k routing-table] (:dht-keyspace state)
                   [id node] (->>
                              routing-table
                              (sequence
                               (comp
                                (filter not-find-noded?)
                                (take 1))))]
             (<! (timeout 400))
             (swap! stateA update-in [:routing-table-find-noded] assoc id {:node node
                                                                           :timestamp (js/Date.now)})
             (take!
              (send-krpc-request
               socket
               (clj->js
                {:t (.randomBytes crypto 4)
                 :y "q"
                 :q "find_node"
                 :a {:id self-idB
                     :target (js/Buffer.from k "hex")  #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}})
               (clj->js node)
               (timeout 2000))
              (fn [{:keys [msg rinfo] :as value}]
                (when value
                  (when-let [nodes (goog.object/getValueByKeys msg "r" "nodes")]
                    (put! nodesB| nodes)))))))

         (recur (timeout (* 4 1000))))

        stop|
        (do :stop)))))

(defn start-sybil
  [{:as opts
    :keys [stateA
           self-idB
           send-krpc-request
           socket
           nodes-bootstrap
           nodesB|
           stop|
           sybils|]}]
  (let [already-sybiledA (atom {})
        nodes| (chan (sliding-buffer 100000)
                     (comp
                      (map (fn [node] [(:id node) node]))
                      (filter (fn [[id node]] (not (get @already-sybiledA id))))))]
    (go
      (<! (a/onto-chan! sybils| (map (fn [i]
                                       (.randomBytes crypto 20))
                                     (range 0 (.. sybils| -buf -n))) true))
      (let [targetB (.randomBytes crypto 20)]
        (doseq [node nodes-bootstrap]
          (take!
           (send-krpc-request
            socket
            (clj->js
             {:t (.randomBytes crypto 4)
              :y "q"
              :q "find_node"
              :a {:id self-idB
                  :target targetB #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}})
            (clj->js node)
            (timeout 2000))
           (fn [{:keys [msg rinfo] :as value}]
             (when value
               (when-let [nodesB (goog.object/getValueByKeys msg "r" "nodes")]
                 (let [nodes (decode-nodes nodesB)]
                   (a/onto-chan! nodes| nodes false))))))))

      (loop [n 8
             i n]
        (let [timeout| (when (= i 0)
                         (timeout 1000))
              [value port] (alts!
                            (concat
                             [stop|]
                             (if timeout|
                               [timeout|]
                               [sybils|]))
                            :priority true)]
          (condp = port

            timeout|
            (recur n n)

            sybils|
            (when-let [sybil-idB value]
              (let [state @stateA
                    [id node] (<! nodes|)]
                (swap! already-sybiledA assoc id true)
                (take!
                 (send-krpc-request
                  socket
                  (clj->js
                   {:t (.randomBytes crypto 4)
                    :y "q"
                    :q "find_node"
                    :a {:id sybil-idB
                        :target sybil-idB #_(gen-neighbor-id (.randomBytes crypto 20) self-idB)}})
                  (clj->js node)
                  (timeout 2000))
                 (fn [{:keys [msg rinfo] :as value}]
                   (when value
                     (when-let [nodesB (goog.object/getValueByKeys msg "r" "nodes")]
                       (let [nodes (decode-nodes nodesB)]
                         (a/onto-chan! nodes| nodes false)))))))
              (recur n (mod (inc i) n)))

            stop|
            (do :stop)))))))