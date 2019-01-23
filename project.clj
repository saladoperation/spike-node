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
  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.10"]
                                  [figwheel-sidecar "0.5.18"]]}}
  ;https://github.com/cursive-ide/cursive/issues/342
  :source-paths ["script" "src/builder" "src/renderer"]
  :cljsbuild {:builds
              {:dev {:source-paths ["src/helpers" "src/main"]
                     :compiler     {:output-to     "resources/main.js"
                                    :optimizations :simple
                                    :main          spike-node.core}}}})
