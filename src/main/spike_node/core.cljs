(ns spike-node.core
  (:require [clojure.string :as str]
            [cljs-node-io.fs :as fs]))

(def electron
  (js/require "electron"))

(.on (.-app electron)
     "ready"
     (fn [_]
       (println (str/join "/" ["file:/"
                               ;TODO deal with advanced optimizations
                               (-> js/__dirname
                                   fs/dirname
                                   fs/dirname)
                               "public/index.html"]))
       (doto
         (electron.BrowserWindow. {})
         (.loadURL (str/join "/" ["file:/"
                                  ;TODO deal with advanced optimizations
                                  (-> js/__dirname
                                      fs/dirname
                                      fs/dirname)
                                  "public/index.html"])))))
