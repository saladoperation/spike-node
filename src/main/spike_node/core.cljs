(ns spike-node.core
  (:require [cljs-node-io.fs :as fs]))

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
                          ;TODO deal with advanced optimizations
                          (path.join (-> js/__dirname
                                         fs/dirname
                                         fs/dirname))
                          (str "file://")))
           window-state.manage))))
