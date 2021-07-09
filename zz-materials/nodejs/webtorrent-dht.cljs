

(defn start
  []
  (go
    (let [#_client #_(Webtorrent.
                      (clj->js
                       {"dhtPort" (+ 6880 peer-index)}))
          #_dht #_(. client -dht)
          stateA (atom nil)
          torrent| (chan (sliding-buffer 100))
          dht (BittorrentDHT.
               (clj->js
                {"nodeId" "9859552c412933025559388fe1c438422e3afee7"}))]
      (reset! stateA {:dht dht
                      :torrent| torrent|})
      (.listen dht (+ 6880 peer-index)
               (fn []))
      (.on dht "ready"
           (fn []
             (println ::dht-ready (+ 6880 peer-index))
             #_(println (.. dht (toJSON) -nodes))))
      (.on dht "announce"
           (fn [peer info-hash]
             (println ::announce)
             (println (.-host peer) (.-port peer))
             (println (.toString info-hash "hex"))
             (->
              (fetchMetadata
               (.toString info-hash "hex")
               (clj->js
                {"maxConns" 10
                 "fetchTimeout" 30000
                 "socketTimeout" 1000
                 "dht" dht}))
              (.then (fn [metadata]
                       (println (.. metadata -info -name (toString "utf-8")))
                       (put! torrent| {:name (.. metadata -info -name (toString "utf-8"))})
                       #_(pprint (js->clj metadata))
                       #_(println (.. metadata -info -pieces (toString "hex")))))
              (.catch (fn [error]
                        (println ::error error))))))
      (.on dht "error"
           (fn [error]
             (println ::dht-error)
             (println error)
             (.destroy dht)))
      stateA)))

