(defproject spike-node "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.439"]
                 [org.clojure/math.combinatorics "0.1.4"]
                 [cljs-node-io "1.1.2"]
                 [cljsjs/mousetrap "1.5.3-0"]
                 [frp "0.1.3"]
                 [me.raynes/fs "1.4.6"]
                 [reagent "0.8.1" :exclusions [cljsjs/react]]]
  :plugins [[lein-ancient "0.6.15"]
            [lein-cljsbuild "1.1.7"]]
  :source-paths ["src/helpers"]
  :profiles {:dev      {:dependencies [[binaryage/devtools "0.9.10"]
                                       [figwheel-sidecar "0.5.18"]]}
             ;:renderer profile works around the following error in REPL.
             ;----  Could not Analyze  src/renderer/spike_node/core.cljs  ----
             ;
             ;  Could not locate spike_node/core__init.class, spike_node/core.clj or spike_node/core.cljc on classpath. Please check that namespaces with dashes use underscores in the Clojure file name.
             ;
             ;----  Analysis Error : Please see src/renderer/spike_node/core.cljs  ----
             :renderer {:source-paths ["src/renderer"]}}
  :cljsbuild {:builds
              {:builder   {:source-paths ["src/builder" "src/helpers"]
                           :compiler     {:output-to "target/main.js"
                                          :main      spike-node.core
                                          :target    :nodejs}}
               :main-dev  {:source-paths ["src/helpers" "src/main"]
                           :compiler     {:output-to     "resources/main.js"
                                          :optimizations :simple
                                          :main          spike-node.core}}
               :main-prod {:source-paths ["src/helpers" "src/main"]
                           :compiler     {:output-to       "resources/main.js"
                                          :optimizations   :simple
                                          :main            spike-node.core
                                          :closure-defines {goog.DEBUG false}}}
               :renderer  {:source-paths ["src/helpers" "src/renderer"]
                           :compiler     {:output-to       "resources/public/js/main.js"
                                          :optimizations   :simple
                                          :main            spike-node.core
                                          :foreign-libs    [{:file           "resources/public/js/index_bundle.js"
                                                             :provides       ["ace"
                                                                              "ace-editor"
                                                                              "katex"
                                                                              "measure"
                                                                              "react"
                                                                              "react-dom"]
                                                             :global-exports {ace        ace
                                                                              ace-editor AceEditor
                                                                              katex      katex
                                                                              measure    Measure
                                                                              react      React
                                                                              react-dom  ReactDOM}}]
                                          :closure-defines {goog.DEBUG false}}}}})
