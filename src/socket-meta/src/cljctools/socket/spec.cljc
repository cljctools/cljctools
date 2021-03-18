(ns cljctools.socket.spec
  (:require
   [clojure.core.async]
   [clojure.spec.alpha :as s]
   [cljctools.socket.protocols :as socket.protocols]))

(s/def ::num-code int?)
(s/def ::reason-text string?)
(s/def ::error any?)

(s/def ::connected keyword?)
(s/def ::ready keyword?)
(s/def ::timeout keyword?)
(s/def ::closed keyword?)
(s/def ::error keyword?)
(s/def ::raw-socket some?)

(s/def ::id any?)
(s/def ::reconnection-timeout int?)
(s/def ::connect? boolean?)
(s/def ::connect-fn ifn?)
(s/def ::disconnect-fn ifn?)
(s/def ::send-fn ifn?)
(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))
(s/def ::mult #?(:clj #(satisfies? clojure.core.async.Mult %)
                 :cljs #(satisfies? cljs.core.async/Mult %)))
(s/def ::send| ::channel)
(s/def ::recv| ::channel)
(s/def ::evt| ::channel)
(s/def ::evt|mult ::mult)
(s/def ::recv|mult ::mult)

(s/def ::host string?)
(s/def ::port int?)
(s/def ::path string?)
(s/def ::url string?)

(s/def ::websocket-opts (s/keys :req [::url]))


(s/def ::tcp-socket-opts (s/or
                          :host-port
                          (s/keys :req [::host
                                        ::port])
                          :path
                          (s/keys :req [::path])))

(s/def ::create-opts-opts (s/or
                           :websocket-opts ::websocket-opts
                           :tcp-socket-opts ::tcp-socket-opts))

(s/def ::create-opts-net-socket ifn?)
(s/def ::create-opts-websocket ifn?)

(s/def ::connect-opts (s/keys :req [::connect-fn
                                    ::disconnect-fn
                                    ::send-fn]))

(s/def ::create-opts (s/keys :req []
                             :opt [::id
                                   ::connect?
                                   ::reconnection-timeout
                                   ::send|
                                   ::recv|
                                   ::evt|
                                   ::evt|mult]))

(s/def ::opts #(if (true? (::connect? %))
                 (s/assert (s/and ::connect-opts ::create-opts) %)
                 (s/assert ::create-opts %)))

(s/def ::socket #(and
                  (satisfies? socket.protocols/Socket %)
                  #?(:clj (satisfies? clojure.lang.IDeref %))
                  #?(:cljs (satisfies? cljs.core/IDeref %))))



