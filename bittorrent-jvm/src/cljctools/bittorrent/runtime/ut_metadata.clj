(ns cljctools.bittorrent.runtime.ut-metadata
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.spec.alpha :as s]
   [cljctools.bittorrent.protocols :as bittorrent.protocols]
   [cljctools.bittorrent.spec :as bittorrent.spec])
  (:import
   (java.io ByteArrayOutputStream ByteArrayInputStream PushbackInputStream)
   (bt.metainfo TorrentId)))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn create
  [{:as opts
    :keys [::send|
           ::recv|
           ::metadata|
           ::bittorrent.spec/infohashBA
           ::bittorrent.spec/peer-idBA]}]
  {:pre [(s/assert ::bittorrent.spec/create-wire-opts opts)]
   :post [(s/assert ::bittorrent.spec/wire %)]}
  (let [stateV (volatile!
                {})

        ex| (chan 1)

        expected-size| (chan 1)
        cut| (chan 1)

        wire
        ^{:type ::wire}
        (reify
          bittorrent.protocols/Wire
          clojure.lang.IDeref
          (deref [_] @stateV))

        release (fn []
                  (close! expected-size|))]))