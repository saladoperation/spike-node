(ns spike-node.core
  (:require [clojure.string :as str]
            [cljs-node-io.fs :as fs]))

(def electron
  (js/require "electron"))

(.on (.-app electron)
     "ready"
     (fn [_]
       (doto
         (electron.BrowserWindow. {})
         (.loadURL (str/join "/" ["file:/"
                                  ;TODO deal with advanced optimizations
                                  (-> js/__dirname
                                      fs/dirname
                                      fs/dirname)
                                  "index.html"])))))
