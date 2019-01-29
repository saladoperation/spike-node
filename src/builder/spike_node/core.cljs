(ns spike-node.core
  (:require [spike-node.helpers :as helpers]))

(def output
  (last js/process.argv))

(def builder
  (js/require "electron-builder"))

(def target
  ["zip"])

(def config
  {:config {:directories      {:output output}
            :fileAssociations {:ext helpers/app-name}}
   :linux  target
   :mac    target})

(-> config
    clj->js
    builder.build)
