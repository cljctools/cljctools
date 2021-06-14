(ns cljctools.impl)

(defn char-code
  [chr]
  (.charCodeAt chr 0))