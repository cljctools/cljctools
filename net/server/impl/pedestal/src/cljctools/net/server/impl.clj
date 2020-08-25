(ns cljctools.net.server.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.jetty.websockets :as pedestal.ws]
   [cognitect.transit :as transit]

   [cljctools.net.server.spec :as server.spec]
   [cljctools.net.server.chan :as server.chan])
  (:import
   org.eclipse.jetty.websocket.api.Session
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.net.URI
   java.nio.ByteBuffer))

(defn create-service
  [opts]
  (let [routes #{["/" :get (fn [_] {:body (clojure-version) :status 200}) :route-name :root]
                 ["/echo" :get #(hash-map :body (pr-str %) :status 200) :route-name :echo]}
        {:keys [service-map ws-paths host port]
         :or {port 8080
              host "0.0.0.0"}} opts]
    (merge
     {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
      ::http/routes #(route/expand-routes routes #_(deref #'service/routes))

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::http/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
      ::http/resource-path "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
      ::http/type :jetty
      ::http/container-options (if ws-paths
                                 {:context-configurator #(pedestal.ws/add-ws-endpoints % ws-paths)}
                                 {})
      ::http/host host
      ::http/port port}
     {:env :dev
      ::http/join? false
      ::http/allowed-origins {:creds true :allowed-origins (constantly true)}}
     service-map)))



; use (.getRemote ws-session) to send directly to one socket
; as it is done in pedestal itself:
; https://github.com/pedestal/pedestal/blob/master/jetty/src/io/pedestal/http/jetty/websockets.clj#L51
; 
; or: need to pass a custom :listener-fn to add-ws-endpoints
; https://github.com/pedestal/pedestal/blob/master/jetty/src/io/pedestal/http/jetty/websockets.clj#L174
; can req response map to session ?

(defn create-proc-ops
  [channels opts]
  (let [{:keys [::server.chan/ops|
                ::server.chan/ops|m
                ::server.chan/ws-evt|
                ::server.chan/ws-evt|m
                ::server.chan/ws-recv|
                ::server.chan/ws-recv|m]} channels
        {:keys [::server.spec/with-websocket-endpoint?]} opts
        server-ops|t (tap server-ops|m (chan 10))
        ws-recv|t (tap ws-recv|m (chan 10))
        ws-evt|t (tap ws-evt|m (chan 10))
        ws-clients (atom {})
        baos (ByteArrayOutputStream. 4096)
        transit-writer (transit/writer baos :json)
        transit-write (fn [data out|]
                        (transit/write transit-writer data)
                        (put! out| (ByteBuffer/wrap (.toByteArray baos)))
                        (.reset baos)
                        out|)
        transit-read (fn [payload]
                       (let [bais (ByteArrayInputStream. payload)
                             transit-reader (transit/reader bais :json)
                             data (transit/read transit-reader)]
                         data))
        ws-paths {"/ws" {:on-connect (pedestal.ws/start-ws-connection
                                      (fn [ws-session send|]
                                        (println ::ws-connected)
                                        (server.chan/ws-connected (::server.chan/ws-evt| channels))
                                        (swap! ws-clients assoc ws-session send|)))
                         :on-text (fn [msg]
                                    (server.chan/ws-recv (::server.chan/ws-recv| channels) (read-string msg)))
                         :on-binary (fn [payload offset length]
                                      (server.chan/ws-recv (::server.chan/ws-recv| channels) (transit-read payload)))
                         :on-error (fn [error]
                                     (println ::ws-error)
                                     (server.chan/ws-error (::server.chan/ws-evt| channels) error))
                         :on-close (fn [num-code reason-text]
                                     (println ::ws-closed)
                                     (server.chan/ws-closed (::server.chan/ws-evt| channels) num-code reason-text))}}
        service (create-service (merge
                                 (when with-websocket-endpoint? {:ws-paths ws-paths})
                                 opts))
        broadcast (fn [data]
                    (doseq [[^Session session send|] @ws-clients]
                      (when (.isOpen session)
                        (transit-write data send|))))
        state (atom {::server nil})
        start-server (fn []
                       (let [server (-> service ;; start with production configuration
                                        http/default-interceptors
                                        http/dev-interceptors
                                        http/create-server
                                        http/start)]
                         (swap! state assoc ::server server)))
        stop-server (fn []
                      (http/stop (::server @state)))]
    (go
      (loop []
        (when-let [[v port] (alts! [ops|t ws-recv|t])]
          (condp = port
            ops|t
            (condp = (:op v)
              ::server.chan/start-server
              (let [{:keys [out|]} v]
                (start-server)
                (put! out| (merge v {:op-status :complete})))

              ::server.chan/stop-server
              (let [{:keys [out|]} v]
                (stop-server)
                (put! out| (merge v {:op-status :complete})))

              ::server.chan/broadcast
              (let []
                (broadcast v)))

            ws-recv|t
            (let []
              (println ::ws-recv|t v)
              #_(broadcast {:data v}))

            ws-evt|t
            (condp = (:op v)
              ::server.chan/ws-connected
              (let []
                (println ::ops ::ws-connected))

              ::server.chan/ws-closed
              (let []
                (println ::ops ::ws-closed))

              ::server.chan/ws-error
              (let []
                (println ::ops ::ws-error)))))
        (recur))
      (println "; proc-ops go-block exiting"))
    #_(reify
        p/Start
        (-start [_])
        (-stop [_])
        p/Broadcast
        (-broadcast [_ opts]
          (broadcast opts))
        clojure.lang.ILookup
        (valAt [_ k] (.valAt _ k nil))
        (valAt [_ k not-found] (.valAt @state k not-found)))))


