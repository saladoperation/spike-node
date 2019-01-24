(ns spike-node.parse.core
  (:refer-clojure :exclude [= not= some])
  (:require [spike-node.parse.derived :as derived]
            [spike-node.parse.primitive :as primitive]))

(def pure
  primitive/pure)

(def mempty
  primitive/mempty)

(def satisfy
  primitive/satisfy)

(def many
  derived/many)

(def some
  derived/some)

(def parse
  derived/parse)

(def any
  derived/any)

(def =
  derived/=)

(def not=
  derived/not=)
