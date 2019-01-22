(ns spike-node.core
  (:require [clojure.string :as str]
            [cljs-node-io.fs :as fs]
            [spike-node.helpers :as helpers]))

(def window-state-keeper
  (js/require "electron-window-state"))

(def app
  (.-app helpers/electron))

(.on app
     "ready"
     (fn [_]
       (let [window-state (window-state-keeper. {})]
         (doto
           (helpers/electron.BrowserWindow. window-state)
           ;TODO use path.join
           (.loadURL (str/join "/" ["file:/"
                                    ;TODO deal with advanced optimizations
                                    (-> js/__dirname
                                        fs/dirname
                                        fs/dirname)
                                    "public/index.html"]))
           window-state.manage))))
