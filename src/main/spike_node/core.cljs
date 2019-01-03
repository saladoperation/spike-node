(ns spike-node.core
  (:require [clojure.string :as str]
            [cljs-node-io.fs :as fs]))

(def electron
  (js/require "electron"))

(def window-state-keeper
  (js/require "electron-window-state"))

(.on (.-app electron)
     "ready"
     (fn [_]
       (let [window-state (window-state-keeper. {})]
         (doto
          (electron.BrowserWindow. window-state)
          (.loadURL (str/join "/" ["file:/"
                                   ;TODO deal with advanced optimizations
                                   (-> js/__dirname
                                       fs/dirname
                                       fs/dirname)
                                   "public/index.html"]))
          window-state.manage))))
