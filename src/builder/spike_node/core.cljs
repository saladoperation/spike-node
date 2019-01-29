(ns spike-node.core
  (:require [cljs-node-io.fs :as fs]
            [spike-node.helpers :as helpers]))

(def builder
  (js/require "electron-builder"))

(def output
  (if (-> js/process.argv
          last
          fs/file?)
    "dist"
    (last js/process.argv)))

(def target
  ["zip"])

(def config
  {:config  {:directories      {:output output}
             :fileAssociations {:ext helpers/app-name}}
   :linux   target
   :mac     target
   :publish "always"})

(-> config
    clj->js
    builder.build)
