(ns repl
  (:require [clojure.string :as str]
            [figwheel-sidecar.repl-api :as repl-api]))

(def argument
  (first *command-line-args*))

(def id
  "app")

(def build
  {:id           "app"
   :source-paths [(str "src/" argument)]
   :compiler     {:output-to            (str "dev-resources/public/js/"
                                             argument
                                             ".js")
                  :main                 "spike-node.core"
                  :asset-path           (str/join "/" ["/js" argument "out"])
                  :source-map-timestamp true
                  :preloads             ['devtools.preload]
                  :external-config      {:devtools/config {:features-to-install :all}}}
   :figwheel     true})

(repl-api/start-figwheel!
  {:build-ids  [id]
   :all-builds [build]})

(repl-api/cljs-repl)
