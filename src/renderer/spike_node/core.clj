(ns spike-node.core)

(defmacro defc
  [function-name & more]
  `(def ~function-name
     (memoize-one (partial vector (fn ~@more)))))
