(ns repl
  (:require [figwheel-sidecar.repl-api :as repl-api]))

(def argument
  (first *command-line-args*))

(def id
  "app")

(def build
  {:id           "app"
   :source-paths [(str "src/" argument)]
   :compiler     {:output-to            (str "dev-resources/public/"
                                             (case argument
                                                   "main" ""
                                                   "js")
                                             "main.js")
                  :main                 "spike-node.core"
                  :asset-path           "/js/out"
                  :source-map-timestamp true
                  :preloads             ['devtools.preload]
                  :external-config      {:devtools/config {:features-to-install :all}}}
   :figwheel     true})

(repl-api/start-figwheel!
  {:build-ids  [id]
   :all-builds [build]})

(repl-api/cljs-repl)
