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

(def main
  (= argument "main"))

(def compiler
  (merge
    {:main                 "spike-node.core"
     :preloads             ['devtools.preload]
     :source-map-timestamp true
     :foreign-libs         [{:file           "resources/public/webpack/index_bundle.js"
                             :provides       ["ace-editor"
                                              "katex"
                                              "react"
                                              "react-dom"]
                             :global-exports {'ace-editor 'AceEditor
                                              'katex      'katex
                                              'react      'React
                                              'react-dom  'ReactDOM}}]
     :external-config      {:devtools/config {:features-to-install :all}}}
    (if main
      {:output-to (get-resources entry)
       :target    :nodejs}
      {:output-to  (get-path (fs/parent renderer-output-dir) entry)
       :output-dir renderer-output-dir
       :asset-path asset-path})))

(def build
  {:id           id
   :source-paths [(get-path "src" argument)]
   :compiler     compiler
   :figwheel     true})

(repl-api/start-figwheel! {:all-builds       [build]
                           :build-ids        [id]
                           :figwheel-options (if main
                                               {}
                                               {:server-port 3450})})

(repl-api/cljs-repl)
