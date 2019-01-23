(ns repl
  (:require [clojure.string :as str]
            [figwheel-sidecar.repl-api :as repl-api]
            [me.raynes.fs :as fs]))

(def argument
  (first *command-line-args*))

(def id
  "app")

(def entry
  "main.js")

(def asset-path
  "js/out")

(def get-path
  (comp (partial str/join "/")
        vector))

(def get-resources
  (partial get-path "resources"))

(def renderer-output-dir
  (get-resources "public" asset-path))

(def builder
  "builder")

(def main
  "main")

(def renderer
  "renderer")

(def compiler
  (merge
    {:main                 "spike-node.core"
     :preloads             ['devtools.preload]
     :source-map-timestamp true
     :foreign-libs         [{:file           "resources/public/webpack/index_bundle.js"
                             :provides       ["ace"
                                              "ace-editor"
                                              "katex"
                                              "react"
                                              "react-dom"]
                             :global-exports {'ace        'ace
                                              'ace-editor 'AceEditor
                                              'katex      'katex
                                              'react      'React
                                              'react-dom  'ReactDOM}}]
     :external-config      {:devtools/config {:features-to-install :all}}}
    ({builder  {:output-to (get-path "target" entry)
                :target    :nodejs}
      main     {:output-to (get-resources entry)
                :target    :nodejs}
      renderer {:output-to  (-> renderer-output-dir
                                fs/parent
                                (get-path entry))
                :output-dir renderer-output-dir
                :asset-path asset-path}}
      argument)))

(def build
  {:id           id
   :source-paths (map (partial get-path "src") [argument "helpers"])
   :compiler     compiler
   :figwheel     true})

(repl-api/start-figwheel! {:all-builds       [build]
                           :build-ids        [id]
                           :figwheel-options ({builder  {}
                                               main     {:server-port 3450}
                                               renderer {:server-port 3451}}
                                               argument)})

(repl-api/cljs-repl)
