(ns cljctools.runtime.core)

(defn char-code
  [chr]
  (.charCodeAt chr 0))