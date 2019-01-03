(ns spike-node.core)

(def electron
  (js/require "electron"))

(.on (.-app electron) "ready" (fn [_]
                                (electron.BrowserWindow. {})))
