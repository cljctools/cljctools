(ns find.app.http
  (:gen-class)
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! <!! >!!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.spec.alpha :as s]
   [clojure.java.io]

   [byte-streams :as bs]
   [aleph.http]
   [manifold.deferred :as d]

   ;; reitit
   [reitit.http]
   [reitit.ring]
   [sieppari.async.core-async] ;; needed for core.async
   #_[sieppari.async.manifold]   ;; needed for manifold
   [muuntaja.interceptor]
   [reitit.coercion.spec]
   [reitit.swagger]
   [reitit.swagger-ui]
   [reitit.dev.pretty]
   [reitit.interceptor.sieppari]
   [reitit.http.coercion]
   [reitit.http.interceptors.parameters]
   [reitit.http.interceptors.muuntaja]
   [reitit.http.interceptors.exception]
   [reitit.http.interceptors.multipart]
   [ring.util.response]
   [cljctools.reitit-cors-interceptor.core]
  ;; Uncomment to use
  ; [reitit.ring.middleware.dev :as dev]
  ; [reitit.ring.spec :as spec]
  ; [spec-tools.spell :as spell]
   [muuntaja.core]
   [spec-tools.core]

   ;;
   ))

(defonce ^:private registry-ref (atom {}))

(defn app
  []
  (reitit.http/ring-handler
   (reitit.http/router
    []
    {;;:reitit.middleware/transform dev/print-request-diffs ;; pretty diffs
       ;;:validate spec/validate ;; enable spec validation for route data
       ;;:reitit.spec/wrap spell/closed ;; strict top-level validation
     :exception reitit.dev.pretty/exception
     :data {:coercion reitit.coercion.spec/coercion
            :access-control {:access-control-allow-origin [#".*"]
                             :access-control-allow-methods #{:get :put :post :delete}}
            :muuntaja muuntaja.core/instance
            :interceptors [;; swagger feature
                           reitit.swagger/swagger-feature
                             ;; query-params & form-params
                           (reitit.http.interceptors.parameters/parameters-interceptor)
                             ;; content-negotiation
                           (reitit.http.interceptors.muuntaja/format-negotiate-interceptor)
                             ;; encoding response body
                           (reitit.http.interceptors.muuntaja/format-response-interceptor)
                             ;; exception handling
                           (reitit.http.interceptors.exception/exception-interceptor)
                             ;; decoding request body
                           (reitit.http.interceptors.muuntaja/format-request-interceptor)
                             ;; coercing response bodys
                           (reitit.http.coercion/coerce-response-interceptor)
                             ;; coercing request parameters
                           (reitit.http.coercion/coerce-request-interceptor)
                             ;; multipart
                           (reitit.http.interceptors.multipart/multipart-interceptor)
                             ;; cors
                           (find.app.cors-interceptor/cors-interceptor)]}})
   (reitit.ring/routes
    (reitit.ring/redirect-trailing-slash-handler #_{:method :add})
    (fn handle-index
      ([request]
       (when (= (:uri request) "/")
         (->
          (ring.util.response/resource-response "index.html" {:root "public"})
          (ring.util.response/content-type "text/html"))))
      ([request respond raise]
       (respond (handle-index request))))
    (reitit.ring/create-resource-handler {:path "/"
                                          :root "public"
                                          :index-files ["index.html"]})
    (reitit.ring/create-default-handler))
   {:executor reitit.interceptor.sieppari/executor}))

(defn start
  [{:keys [::port] :or {port 4081} :as opts}]
  (go
    (when-not (get @registry-ref port)
      (let [server
            (aleph.http/start-server
             (aleph.http/wrap-ring-async-handler (app opts)  #_#'app)
             {:port port :host "0.0.0.0"})]
        (swap! registry-ref assoc port server)
        (println (format "server running in port %d" port))))))

(defn stop
  [{:keys [::port] :or {port 4081} :as opts}]
  (go
    (let [server (get @registry-ref port)]
      (when server
        (.close server)
        (swap! registry-ref dissoc port)
        (println (format "stopped server on port %d" port))))))