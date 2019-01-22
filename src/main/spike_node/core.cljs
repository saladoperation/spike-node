(ns spike-node.core
  (:require [aid.core :as aid]
            [cljs-node-io.fs :as fs]))

(def electron
  (js/require "electron"))

(def path
  (js/require "path"))

(def window-state-keeper
  (js/require "electron-window-state"))

(def channel
  "channel")

(def app
  (.-app electron))

(.on app
     "ready"
     (fn [_]
       (let [window-state (window-state-keeper. {})]
         (doto
           (electron.BrowserWindow. window-state)
           (.loadURL (->> "public/index.html"
                          (path.join (aid/if-else (comp (partial = "resources")
                                                        fs/basename)
                                                  (comp fs/dirname
                                                        fs/dirname)
                                                  js/__dirname))
                          (str "file://")))
           window-state.manage))))
