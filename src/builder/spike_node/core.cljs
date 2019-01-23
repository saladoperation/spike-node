(ns spike-node.core)

(def output
  (last js/process.argv))

(def builder
  (js/require "electron-builder"))

(def config
  {:config {:directories {:output output}
            :linux       {:target ["zip"]}}})

(builder.build (clj->js config))
