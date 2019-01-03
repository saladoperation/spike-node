(ns repl
  (:require [figwheel-sidecar.repl-api :as repl-api]
            [me.raynes.fs :as fs]))

(def argument
  (first *command-line-args*))

(def id
  "app")

(def entry
  "main.js")

(def asset-path
  "js/out")

(def renderer-output-dir
  ;TODO use join-paths
  (str "resources/public/" asset-path))

(def build
  {:id           id
   :source-paths [(str "src/" argument)]
   :compiler     (merge {:output-to            (str "resources/" entry)
                         :main                 "spike-node.core"
                         :target               :nodejs
                         :preloads             ['devtools.preload]
                         :source-map-timestamp true
                         :external-config      {:devtools/config {:features-to-install :all}}}
                        (case argument
                          "main" {}
                          {:output-to  (str (fs/parent renderer-output-dir)
                                            "/"
                                            entry)
                           :output-dir renderer-output-dir
                           :asset-path asset-path}))
   :figwheel     true})

(repl-api/start-figwheel! {:all-builds       [build]
                           :build-ids        [id]
                           :figwheel-options (case argument
                                               "main" {}
                                               {:server-port 3450})})

(repl-api/cljs-repl)
