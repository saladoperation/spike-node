(ns spike-node.helpers
  (:require [clojure.string :as str]))

;TODO move electron to main and renderer
#?(:cljs (def electron
           (js/require "electron")))

(def app-name
  "spike-node")

(def channel
  "channel")

(def get-path
  (comp (partial str/join "/")
        vector))

(def resources
  "resources")

(def public
  "public")
