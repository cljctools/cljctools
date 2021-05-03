(ns find.bittorrent.sybil
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
   [find.bittorrent.core :refer [decode-nodes
                                 send-krpc
                                 gen-neighbor-id
                                 encode-nodes
                                 send-krpc-request-fn]]))

(defonce crypto (js/require "crypto"))
(defonce dgram (js/require "dgram"))
(defonce bencode (js/require "bencode"))

(defn start
  [{:as opts
    :keys [stateA
           nodes-bootstrap
           nodesB|
           stop|
           sybils|
           infohash|

           count-messages-sybilA]}]
  (let [already-sybiledA (atom {})
        self-idB (.randomBytes crypto 20)
        self-id (.toString self-idB "hex")

        port 6882
        address "0.0.0.0"
        socket (.createSocket dgram "udp4")

        nodes| (chan (sliding-buffer 100000)
                     (comp
                      (filter (fn [node]
                                (and (not= (:address node) address)
                                     (not= (:id node) self-id)
                                     #_(not= 0 (js/Buffer.compare (:id node) self-id))
                                     (< 0 (:port node) 65536))))
                      (filter (fn [node] (not (get @already-sybiledA (:id node)))))
                      (map (fn [node] [(:id node) node]))))

        msg| (chan (sliding-buffer 1024))
        msg|mult (mult msg|)

        send-krpc-request (send-krpc-request-fn {:msg|mult msg|mult})

        routing-tableA (atom {})]

    (doto socket
      (.bind port address)
      (.on "listening"
           (fn []
             (println (format "sybil listening on %s:%s" address port))))
      (.on "message"
           (fn [msgB rinfo]
             (swap! count-messages-sybilA inc)
             (try
               (put! msg| {:msg (.decode bencode msgB)
                           :rinfo rinfo})
               (catch js/Error error (do nil)))))
      (.on "error"
           (fn [error]
             (println ::socket-error)
             (println error))))

    (go
      (<! (a/onto-chan! sybils| (map (fn [i]
                                       (.randomBytes crypto 20))
                                     (range 0 (.. sybils| -buf -n))) true))
      (doseq [node nodes-bootstrap]
        (take!
         (send-krpc-request
          socket
          (clj->js
           {:t (.randomBytes crypto 4)
            :y "q"
            :q "find_node"
            :a {:id self-idB
                :target (gen-neighbor-id self-idB (.randomBytes crypto 20))}})
          (clj->js node)
          (timeout 2000))
         (fn [{:keys [msg rinfo] :as value}]
           (when value
             (when-let [nodesB (goog.object/getValueByKeys msg "r" "nodes")]
               (let [nodes (decode-nodes nodesB)]
                 (swap! routing-tableA merge (into {} (map (fn [node] [(:id node) node]) nodes)))
                 (a/onto-chan! nodes| nodes false)))))))

      (loop [n 16
             i n]
        (let [timeout| (when (= i 0)
                         (timeout 500))
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
                        :target (gen-neighbor-id (:idB node) self-idB)}})
                  (clj->js node)
                  (timeout 2000))
                 (fn [{:keys [msg rinfo] :as value}]
                   (when value
                     (when-let [nodesB (goog.object/getValueByKeys msg "r" "nodes")]
                       (let [nodes (decode-nodes nodesB)]
                         (a/onto-chan! nodes| nodes false)))))))
              (recur n (mod (inc i) n)))

            stop|
            (do :stop)))))


    (let [msg|tap (tap msg|mult (chan (sliding-buffer 512)))]
      (go
        (loop []
          (when-let [{:keys [msg rinfo] :as value} (<! msg|tap)]
            (let [msg-y (some-> (. msg -y) (.toString "utf-8"))
                  msg-q (some-> (. msg -q) (.toString "utf-8"))]
              (cond

                (and (= msg-y "q")  (= msg-q "ping"))
                (let [txn-idB  (. msg -t)
                      node-idB (.. msg -a -id)]
                  (if (or (not txn-idB) (not= (.-length node-idB) 20))
                    (do nil :invalid-data)
                    (send-krpc
                     socket
                     (clj->js
                      {:t txn-idB
                       :y "r"
                       :r {:id (gen-neighbor-id node-idB self-idB)}})
                     rinfo)))

                (and (= msg-y "q")  (= msg-q "find_node"))
                (let [txn-idB  (. msg -t)
                      node-idB (.. msg -a -id)
                      target-idB (.. msg -a -target)]
                  (if (or (not txn-idB) (not= (.-length node-idB) 20))
                    (println "invalid query args: find_node")
                    (send-krpc
                     socket
                     (clj->js
                      {:t txn-idB
                       :y "r"
                       :r {:id (gen-neighbor-id node-idB self-idB)
                           :nodes (encode-nodes (take 8 @routing-tableA))}})
                     rinfo)))

                (and (= msg-y "q")  (= msg-q "get_peers"))
                (let [infohashB  (.. msg -a -info_hash)
                      txn-idB (. msg -t)
                      node-idB (.. msg -a -id)
                      tokenB (.slice infohashB 0 4)]
                  (if (or (not txn-idB) (not= (.-length node-idB) 20) (not= (.-length infohashB) 20))
                    (println "invalid query args: get_peers")
                    (do
                      (put! infohash| {:infohashB infohashB
                                       :rinfo rinfo})
                      #_(send-krpc
                         socket
                         (clj->js
                          {:t txn-idB
                           :y "r"
                           :r {:id (gen-neighbor-id infohashB self-idB)
                               :nodes (encode-nodes (take 8 @routing-tableA))
                               :token tokenB}})
                         rinfo))))

                (and (= msg-y "q")  (= msg-q "announce_peer"))
                (let [infohashB   (.. msg -a -info_hash)
                      txn-idB (. msg -t)
                      node-idB (.. msg -a -id)
                      tokenB (.slice infohashB 0 4)]
                  (cond
                    (not txn-idB)
                    (println "invalid query args: announce_peer")

                    #_(not= (-> infohashB (.slice 0 4) (.toString "hex")) (.toString tokenB "hex"))
                    #_(println "announce_peer: token and info_hash don't match")

                    :else
                    (do
                      #_(send-krpc
                         socket
                         (clj->js
                          {:t txn-idB
                           :y "r"
                           :r {:id (gen-neighbor-id infohashB self-idB)}})
                         rinfo)
                      (put! infohash| {:infohashB infohashB
                                       :rinfo rinfo}))))

                :else
                (do nil)))


            (recur)))))))