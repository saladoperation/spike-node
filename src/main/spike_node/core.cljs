(ns spike-node.core)

(def electron
  (js/require "electron"))

(def app (.-app electron))

(.on app "ready" (fn [_]))
