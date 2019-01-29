(ns spike-node.helpers
  (:require [clojure.string :as str]))

(def app-name
  "spike-node")

#?(:cljs (def electron
           (js/require "electron")))

(def channel
  "channel")

(def get-path
  (comp (partial str/join "/")
        vector))

(def resources
  "resources")

(def public
  "public")
