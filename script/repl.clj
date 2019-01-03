(ns repl
  (:require [figwheel-sidecar.repl-api :as repl-api]))

(def argument
  (first *command-line-args*))

(def id
  "app")

(def build
  {:id           id
   :source-paths [(str "src/" argument)]
   :compiler     {:output-to            (str "resources/public/"
                                             (case argument
                                                   "main" ""
                                                   "js")
                                             "main.js")
                  :main                 "spike-node.core"
                  :target               :nodejs
                  :preloads             ['devtools.preload]
                  :source-map-timestamp true
                  :external-config      {:devtools/config {:features-to-install :all}}}
   :figwheel     true})

(repl-api/start-figwheel!
  {:build-ids  [id]
   :all-builds [build]})

(repl-api/cljs-repl)
