(defproject spike-node "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.439"]
                 [cljs-node-io "1.1.2"]
                 [cljsjs/mousetrap "1.5.3-0"]
                 [frp "0.1.3"]
                 [me.raynes/fs "1.4.6"]
                 [nano-id "0.9.3"]
                 [reagent "0.8.1" :exclusions [cljsjs/react]]]
  :plugins [[lein-ancient "0.6.15"]]
  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.10"]
                                  [figwheel-sidecar "0.5.18"]]}}
  :source-paths ["src/main" "src/renderer"])
