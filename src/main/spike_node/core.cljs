(ns spike-node.core
  (:require [clojure.string :as str]
            [cljs-node-io.fs :as fs]))

(def electron
  (js/require "electron"))

(def channel
  "channel")

(def window-state-keeper
  (js/require "electron-window-state"))

(def app
  (.-app electron))

(.on app
     "ready"
     (fn [_]
       (let [window-state (window-state-keeper. {})
             window (electron.BrowserWindow. window-state)]
         (doto
           window
           (.webContents.on "did-finish-load"
                            #(->> "documents"
                                  (.getPath app)
                                  (.webContents.send window channel)))
           (.loadURL (str/join "/" ["file:/"
                                    ;TODO deal with advanced optimizations
                                    (-> js/__dirname
                                        fs/dirname
                                        fs/dirname)
                                    "public/index.html"]))
           window-state.manage))))
