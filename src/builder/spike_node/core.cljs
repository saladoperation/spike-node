(ns spike-node.core)

(def output
  (last js/process.argv))

(def builder
  (js/require "electron-builder"))

(def config
  {:linux {:directories {:output output}
           :target      ["deb"]}})
