(ns spike-node.core
  (:require [clojure.string :as str]
            [cljs.tools.reader.edn :as edn]
            [ace-editor]
            [aid.core :as aid]
            [cats.core :as m]
            [cljs-node-io.core :refer [slurp]]
            [cljs-node-io.fs :as fs]
            [cljsjs.mousetrap]
            [com.rpl.specter :as s]
            [frp.clojure.core :as core]
            [frp.core :as frp]
            [frp.window :as window]
            [katex]
            [loom.graph :as graph]
            [reagent.core :as r]
            [spike-node.helpers :as helpers]))

(frp/defe previous-file-path
          down
          up
          left
          right
          normal-escape
          insert
          command
          editor-keydown
          editor-keyup
          command-keydown
          insert-typing
          command-typing
          submission
          undo
          redo)

(def os
  (js/require "os"))

(def path
  (js/require "path"))

(def directory-event
  (m/<$> fs/dirname previous-file-path))

(def default-path
  (path.join (.homedir os) "Documents"))

(def directory-behavior
  (frp/stepper default-path directory-event))

(def relative-path
  (->> submission
       (m/<$> (partial (aid/flip str/split) #" "))
       (core/filter (comp (partial = ":e")
                          first))
       (m/<$> last)))

(def open
  (m/<$> (comp (partial apply path.join)
               reverse)
         (frp/snapshot relative-path directory-behavior)))

(def edn?
  #(try (do (edn/read-string %)
            true)
        ;TODO limit the error
        (catch js/Error _
          false)))

(def valid-file?
  ;TODO validate the keys and values
  (comp edn?
        slurp))

(def current-file-path
  (m/<> (core/remove fs/fexists? open)
        (core/filter (aid/build and
                                fs/fexists?
                                valid-file?)
                     open)))

(defn get-cursor-event
  [plus minus]
  (->> (m/<> (aid/<$ (aid/if-then pos?
                                  dec)
                     minus)
             (aid/<$ inc plus))
       ;TODO extract a function
       (frp/accum 0)))

(def get-cursor-behavior
  (partial frp/stepper 0))

(def x-event
  (get-cursor-event right left))

(def y-event
  (get-cursor-event down up))

(def x-behavior
  (get-cursor-behavior x-event))

(def y-behavior
  (get-cursor-behavior y-event))

(def initial-table
  {:y-x (sorted-map)
   :x-y (sorted-map)})

(def initial-content
  [[{:node initial-table
     :edge (graph/digraph)}]
   []])

(def escape?
  (partial = "Escape"))

(def command-escape
  (core/filter escape? command-keydown))

(def command-exit
  (m/<> command-escape submission))

(def llast
  (comp last
        last))

(def normal
  (->> insert
       (m/<$> vector)
       (m/<> editor-keydown)
       (core/partition 2 1)
       (core/filter (aid/build and
                               (comp (partial = "")
                                     ffirst)
                               (comp escape?
                                     llast)))
       (m/<> normal-escape command-exit)))

(def undo-size
  10)

(defn get-error
  [s]
  (try (do (js/katex.renderToString s)
           "")
       (catch js/katex.ParseError error
         (str error))))

(def valid-expression?
  (comp empty?
        get-error))

(def valid
  (core/filter valid-expression? insert-typing))

(def typed
  (->> (m/<> x-event y-event)
       (aid/<$ false)
       (m/<> (aid/<$ true valid))
       (frp/stepper false)))

(def action
  (->> valid
       (frp/stepper "")
       (frp/snapshot normal x-behavior y-behavior typed)
       (m/<$>
         (fn [[_ x y typed* s]]
           (if typed*
             (comp
               (partial s/transform* s/FIRST (partial take undo-size))
               (aid/transfer* [s/FIRST s/BEFORE-ELEM]
                              (comp (partial s/setval*
                                             [:node
                                              (s/multi-path [:y-x
                                                             (s/keypath [y
                                                                         x])]
                                                            [:x-y
                                                             (s/keypath [x
                                                                         y])])
                                              :text]
                                             s)
                                    ffirst)))
             identity)))))

(def multiton?
  (comp (partial < 1)
        count))

(def lfirst
  (comp first
        last))

(def content
  (frp/accum initial-content
             (m/<> action
                   (aid/<$ (aid/if-then (comp multiton?
                                              first)
                                        (comp (partial s/transform*
                                                       s/FIRST
                                                       rest)
                                              (aid/transfer* [s/LAST
                                                              s/BEFORE-ELEM]
                                                             ffirst)))
                           undo)
                   (aid/<$ (comp (partial s/transform* s/LAST rest)
                                 (aid/transfer* [s/FIRST s/BEFORE-ELEM]
                                                lfirst))
                           redo))))

(def current-node
  (->> (frp/snapshot valid
                     x-behavior
                     y-behavior)
       (m/<$> (fn [[s x y]]
                (partial s/setval* (s/keypath [x y]) s)))
       (m/<> (m/<$> (comp constantly
                          (partial s/transform* s/MAP-VALS :text)
                          :x-y
                          :node
                          ffirst)
                    content))
       (frp/accum {})
       (frp/stepper {})))

(def insert-text
  (->> insert-typing
       (m/<> (m/<$> (fn [[x y m]]
                      (get m [x y] ""))
                    (frp/snapshot x-event y-behavior current-node))
             (m/<$> (fn [[y x m]]
                      (get m [x y] ""))
                    (frp/snapshot y-event x-behavior current-node)))
       (frp/stepper "")))

(def error
  (m/<$> get-error insert-text))

(def command-text
  (->> command-exit
       (aid/<$ "")
       (m/<> command-typing)
       (frp/stepper "")))

(def font-size
  18)

(def cursor-size
  (* font-size 3))

(def mode
  (frp/stepper :normal (m/<> (aid/<$ :normal normal)
                             (aid/<$ :insert insert)
                             (aid/<$ :command command))))

(def editor-command
  (->> editor-keyup
       (core/filter (partial = ":"))
       (aid/<$ false)
       (m/<> (m/<$> (comp (partial = ":")
                          last)
                    editor-keydown))
       (frp/stepper false)))

(defn editor
  [& _]
  (let [state (atom {})]
    (r/create-class
      {:component-did-mount
       (fn [_]
         (->
           @state
           :editor
           .textInput.getElement
           (.addEventListener
             "keydown"
             #(editor-keydown [(.keyBinding.getStatusText (:editor @state)
                                                          (:editor @state))
                               (.-key %)])))
         (-> @state
             :editor
             .textInput.getElement
             (.addEventListener
               "keyup"
               (fn [event*]
                 (editor-keyup (.keyBinding.getStatusText (:editor @state)
                                                          (:editor @state)))
                 (swap! state
                        (partial s/setval*
                                 :backtick
                                 (-> event*
                                     .-key
                                     (= "`"))))))))
       :component-did-update
       (fn [_]
         (if (-> @state
                 :mode
                 (= :normal))
           (-> @state
               :editor
               .blur)))
       :reagent-render
       (fn [mode* text*]
         (swap! state (partial s/setval* :mode mode*))
         [:> ace-editor
          {:commands         [{:name    "`"
                               :bindKey "`"
                               :exec    aid/nop}
                              {:bindKey "l"
                               :exec    #(.insert (:editor @state)
                                                  (aid/casep @state
                                                    :backtick "\\lambda"
                                                    "l"))}]
           :focus            (= :insert mode*)
           :keyboard-handler "vim"
           :mode             "latex"
           :on-change        #(insert-typing %)
           :on-focus         #(insert
                                (.keyBinding.getStatusText (:editor @state)
                                                           (:editor @state)))
           :ref              #(if %
                                (swap! state
                                       (partial s/setval*
                                                :editor
                                                (.-editor %))))
           :style            {:font-size font-size
                              :height    "100%"
                              :width     "100%"}
           :theme            "terminal"
           :value            text*}])})))

(defn math
  [s]
  [:div
   {:dangerouslySetInnerHTML
    {:__html (js/katex.renderToString s
                                      #js {:displayMode true})}}])

(def align
  (partial (aid/flip str/join) ["\\begin{aligned}" "\\end{aligned}"]))

(defn math-node
  [[x y] s]
  [:foreignObject {:x (* x cursor-size)
                   :y (* y cursor-size)}
   [math (align s)]])

(def background-color
  "black")

(def maximum-z-index
  ;https://stackoverflow.com/questions/491052/minimum-and-maximum-value-of-z-index
  2147483647)

(def get-percent
  (comp (partial (aid/flip str) "%")
        (partial * 100)))

(def left-pane
  0.5)

(def right-pane
  (- 1 left-pane))

(def message-style
  {:background-color background-color
   :border           "none"
   :bottom           0
   :color            "white"
   :position         "absolute"
   :z-index          maximum-z-index})

(defn input-component
  [_]
  (r/create-class
    {:component-did-update
     #(-> %
          r/dom-node
          .focus)
     :reagent-render
     (fn [s]
       [:input
        {:on-change   #(-> %
                           .-target.value
                           command-typing)
         :on-key-down #(-> %
                           .-key
                           command-keydown)
         :style       (s/setval :width (get-percent left-pane) message-style)
         :value       s}])}))

(defn command-component
  [mode* command-text*]
  [:form {:style     {:display (case mode*
                                 :command "block"
                                 "none")}
          :on-submit #(submission command-text*)}
   [input-component command-text*]])

(defn graph-component
  [current-node* x y]
  (s/setval s/END
            (mapv (comp vec
                        (partial cons math-node))
                  current-node*)
            [:svg {:style {:height "100%"
                           :width  "100%"}}
             [:rect {:height cursor-size
                     :stroke "red"
                     :width  cursor-size
                     :x      (* x cursor-size)
                     :y      (* y cursor-size)}]]))

(defn error-component
  [error* editor-command*]
  [:div {:style (merge message-style {:display (if (or (empty? error*)
                                                       editor-command*)
                                                 "none"
                                                 "block")
                                      :width   (get-percent right-pane)})}
   error*])

(defn app-component
  [graph-view* command-view* editor-view* error-view*]
  [:div {:style {:background-color background-color
                 :color            "white"
                 :display          "flex"
                 :height           "100%"
                 :overflow         "hidden"
                 :width            "100%"}}
   [:div {:style {:width (get-percent left-pane)}}
    graph-view*
    command-view*]
   [:div {:style {:width (get-percent right-pane)}}
    editor-view*
    error-view*]])

(def graph-view
  ((aid/lift-a graph-component) current-node x-behavior y-behavior))

(def command-view
  ((aid/lift-a command-component) mode command-text))

(def editor-view
  ((aid/lift-a (partial vector editor)) mode insert-text))

(def error-view
  ((aid/lift-a error-component) error editor-command))

(def app-view
  ((aid/lift-a app-component) graph-view command-view editor-view error-view))

(frp/run previous-file-path current-file-path)

(frp/run (comp aid/funcall
               :prevent-default)
         window/submit)

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app"))
         app-view)

(.ipcRenderer.on helpers/electron helpers/channel (comp directory-event
                                                        last
                                                        vector))

(defn bind
  [s e]
  (js/Mousetrap.bind s #(e)))

(def bind-keymap
  (partial run! (partial apply bind)))

(def keymap
  {":"      command
   "ctrl+r" redo
   "escape" normal-escape
   "h"      left
   "i"      insert
   "j"      down
   "k"      up
   "l"      right
   "space"  insert
   "u"      undo})

(bind-keymap keymap)

(frp/activate)
