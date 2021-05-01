(ns find.bittorrent.sample-infohashes
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

   [find.bittorrent.core :refer [decode-samples
                                 decode-nodes]]))

(defonce crypto (js/require "crypto"))

(defn start-sampling
  [{:as opts
    :keys [stateA
           self-idB
           nodes-to-sample|
           infohash|
           send-krpc-request
           socket]}]
  (let [stop| (chan 1)
        nodes| (chan 1
                     (comp
                      (filter (fn [node]
                                (not (get (:routing-table-sampled @stateA) (:id node)))))))]
    (pipe nodes-to-sample| nodes|)

    (go
      (loop [n 4
             i n
             ts (js/Date.now)
             time-total 0]

        (let [timeout| (when (and (= i 0) (< time-total 1000))
                         (timeout (+ time-total (- 1000 time-total))))
              [value port] (alts! (if timeout|
                                    [timeout|]
                                    [nodes|])
                                  :priority true)]
          (when (or value (= port timeout|))
            (condp = port

              timeout|
              (do nil
                  (recur n n (js/Date.now) 0))

              nodes|
              (let [node value]
                (swap! stateA update-in [:routing-table-sampled] assoc (:id node) (merge node
                                                                                         {:timestamp (js/Date.now)}))
                (when-let [value (<! (send-krpc-request
                                      socket
                                      (clj->js
                                       {:t (.randomBytes crypto 4)
                                        :y "q"
                                        :q "sample_infohashes"
                                        :a {:id self-idB
                                            :target (.randomBytes crypto 20)}})
                                      (clj->js node)
                                      (timeout 2000)))]
                  (let [{:keys [msg rinfo]} value
                        {:keys [interval nodes num samples]} (:r (js->clj msg :keywordize-keys true))]
                    (when samples
                      (doseq [infohashB (decode-samples samples)]
                        #_(println :info_hash (.toString infohashB "hex"))
                        (put! infohash| {:infohashB infohashB
                                         :rinfo rinfo})))
                    (when interval
                      (swap! stateA update-in [:routing-table-sampled (:id node)] merge {:interval interval}))
                    #_(when nodes
                        (put! nodes-to-sample| nodes))))

                (recur n (mod (inc i) n) (js/Date.now) (+ time-total (- ts (js/Date.now))))))))))))



; ask for infohashes, then for metadata using one tcp connection
#_(let [stop| (chan 1)
        nodes| (chan 1
                     (comp
                      (filter (fn [node]
                                (not (get (:routing-table-sampled @stateA) (:id node)))))))
        nodes|mix (mix nodes|)
        cancel-channelsA (atom [])
        release (fn []
                  (doseq [cancel| @cancel-channelsA]
                    (close! cancel|)))]
    (swap! procsA conj stop|)
    (admix nodes|mix nodes-to-sample|)
    (go
      (loop [n 8
             i n
             ts (js/Date.now)
             time-total 0]
        (when (and (= i 0) (< time-total 2000))
          (a/toggle nodes|mix {nodes-to-sample| {:pause true}})
          (<! (timeout (+ time-total (- 2000 time-total))))
          (a/toggle nodes|mix {nodes-to-sample| {:pause false}})
          (recur n n (js/Date.now) 0))
        (alt!
          nodes|
          ([node]
           (let []
             (swap! stateA update-in [:routing-table-sampled] assoc (:id node) (merge node
                                                                                      {:timestamp (js/Date.now)}))
             (let [alternative-infohash-targetB (.randomBytes crypto 20)
                   txn-idB (.randomBytes crypto 4)]
               #_(println :sampling-a-node)
               (when-let [value (<! (send-krpc-request
                                     socket
                                     (clj->js
                                      {:t txn-idB
                                       :y "q"
                                       :q "sample_infohashes"
                                       :a {:id self-idB
                                           :target alternative-infohash-targetB}})
                                     (clj->js node)
                                     (timeout 2000)))]
                 (let [{:keys [msg rinfo]} value
                       {:keys [interval nodes num samples]} (:r (js->clj msg :keywordize-keys true))]
                   (when samples
                     (let [cancel| (chan 1)
                           _ (swap! cancel-channelsA conj cancel|)
                           infohashes (decode-samples samples)
                           _ (doseq [infohashB infohashes]
                               (put! infohash| {:infohashB infohashB
                                                :rinfo rinfo}))]
                       (doseq [infohashB infohashes]
                         (<! (timeout 500))
                         (take! (request-metadata node self-idB infohashB cancel|)
                                (fn [value]
                                  (println :result value))))

                       #_(println :torrents)
                       #_(pprint (<! (request-metadata-multiple node self-idB infohashes cancel|)))))
                   (when interval
                     (println (:id node) interval)
                     (swap! stateA update-in [:routing-table-sampled (:id node)] merge {:interval interval}))
                   #_(when nodes
                       (put! nodes-to-sample| nodes))))))

           (recur n (mod (inc i) n) (js/Date.now) (+ time-total (- ts (js/Date.now)))))

          stop|
          (do :stop)))
      (release)))