(ns repl
  (:require [clojure.string :as str]))

(def argument
  (first *command-line-args*))

(def build
  {:id           "app"
   :source-paths ["src"]
   :compiler     {:output-to            (str "public/js/" argument ".js")
                  :main                 "spike-node.core"
                  :asset-path           (str/join "/" ["/js" argument "out"])
                  :source-map-timestamp true
                  :preloads             ['devtools.preload]
                  :external-config      {:devtools/config {:features-to-install :all}}}
   :figwheel     true})
