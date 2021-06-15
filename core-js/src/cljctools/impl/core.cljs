(ns cljctools.impl.core)

(defn char-code
  [chr]
  (.charCodeAt chr 0))