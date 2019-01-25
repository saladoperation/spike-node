(ns repl
  (:require [figwheel-sidecar.repl-api :as repl-api]
            [me.raynes.fs :as fs]
            [spike-node.helpers :as helpers]))

(def argument
  (first *command-line-args*))

(def id
  "app")

(def entry
  "main.js")

(def asset-path
  "js/out")

(def get-resources
  (partial helpers/get-path helpers/resources))

(def get-public
  (partial get-resources helpers/public))

(def renderer-output-dir
  (get-public asset-path))

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
     ;TODO use npm-deps when npm-deps becomes stable
     :foreign-libs         [{:file           (get-public "webpack/index_bundle.js")
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
    ({builder  {:output-to (helpers/get-path "target" entry)
                :target    :nodejs}
      main     {:output-to (get-resources entry)
                :target    :nodejs}
      renderer {:output-to  (-> renderer-output-dir
                                fs/parent
                                (helpers/get-path entry))
                :asset-path asset-path}}
      argument)))

(def build
  {:id           id
   :source-paths (map (partial helpers/get-path "src") [argument "helpers"])
   :compiler     compiler
   :figwheel     true})

(repl-api/start-figwheel! {:all-builds       [build]
                           :build-ids        [id]
                           :figwheel-options ({builder  {}
                                               main     {:server-port 3450}
                                               renderer {:server-port 3451}}
                                               argument)})

(repl-api/cljs-repl)
