(ns spike-node.core
  (:require [spike-node.helpers :as helpers]))

(def output
  (last js/process.argv))

(def builder
  (js/require "electron-builder"))

(def config
  {:config {:directories      {:output output}
            :fileAssociations {:ext helpers/app-name}
            :linux            {:target ["zip"]}
            :mac              {:target ["zip"]}}})

(-> config
    clj->js
    builder.build)
