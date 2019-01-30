(ns spike-node.core)

(defmacro c
  [& more]
  `(memoize-one (partial vector (fn ~@more))))

(defmacro defc
  [function-name & more]
  `(def ~function-name
     (c ~@more)))
