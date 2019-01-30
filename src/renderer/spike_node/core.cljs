(ns spike-node.core
  (:require [clojure.string :as str]
            [cljs.tools.reader.edn :as edn]
            ace
            ace-editor
            [aid.core :as aid]
            [cats.core :as m]
            [clojure.math.combinatorics :as combo]
            [cljs-node-io.core :refer [slurp spit]]
            [cljs-node-io.fs :as fs]
            cljsjs.mousetrap
            [com.rpl.specter :as s]
            [cuerdas.core :as cuerdas]
            [frp.clojure.core :as core]
            [frp.core :as frp]
            [goog.object :as object]
            katex
            [loom.graph :as graph]
            [oops.core :refer [oget+]]
            [reagent.core :as r]
            [spike-node.helpers :as helpers]
            [spike-node.loom :as loom]
            [spike-node.parse.core :as parse])
  (:require-macros [spike-node.core :refer [defc]]))

(frp/defe loop-file
          loop-file-path
          down
          up
          left
          right
          dom
          normal-escape
          insert-normal
          insert-insert
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

(def home
  (.homedir os))

(def config-path
  (path.join home (str "." helpers/app-name "rc")))

(def config-commands
  (aid/casep config-path
    fs/fexists? (->> config-path
                     slurp
                     str/split-lines
                     (remove empty?)
                     (map (partial str ":")))
    []))

(def token
  (->> (m/<> (parse/not= \ )
             ((aid/lift-a vector)
               (parse/= \\)
               (parse/= \ )))
       parse/some
       (m/<$> (partial apply str))))

(def delimiter
  (parse/some (parse/= \ )))

(def argument
  ((aid/lift-a (comp last
                     vector))
    delimiter
    token))

(def start
  ((aid/lift-a (partial apply str))
    (parse/= \:)
    token))

(def command-parser
  (->> argument
       parse/many
       ((aid/lift-a cons) start)))

(def parse-command
  (comp first
        (partial parse/parse command-parser)))

(def default-path
  (path.join home "Documents"))

(def loop-directory-event
  (m/<$> fs/dirname loop-file-path))

(def directory-behavior
  (frp/stepper default-path loop-directory-event))

(def get-potential-path
  #(->>
     directory-behavior
     (frp/snapshot
       (->> submission
            (m/<$> parse-command)
            (core/filter (aid/build and
                                    (comp (partial = 2)
                                          count)
                                    (comp (partial (aid/flip str/starts-with?)
                                                   (str ":" %))
                                          first)))
            (m/<$> second)))
     (m/<$> (comp (aid/if-then-else (comp fs/absolute?
                                          last)
                                    last
                                    (partial apply path.join))
                  reverse))
     core/dedupe))

(def read-file
  (partial edn/read-string
           {:readers {'spike-node.core.Table
                      (partial s/transform*
                               s/MAP-VALS
                               (partial into
                                        (sorted-map)))
                      'loom.graph.BasicEditableDigraph
                      (comp loom/digraph
                            :adj)}}))

(def edn?
  #(try (do (read-file %)
            true)
        ;TODO limit the error
        (catch js/Error _
          false)))

(def valid-file?
  ;TODO validate the keys and values
  (comp edn?
        slurp))

(def potential-file-path
  (get-potential-path "e"))

(def current-file-path
  (core/filter (aid/build or
                          (complement fs/fexists?)
                          (aid/build and
                                     fs/fexists?
                                     valid-file?))
               potential-file-path))

(def current-directory-path
  (->> "cd"
       get-potential-path
       (core/filter fs/fexists?)
       (m/<> (m/<$> fs/dirname current-file-path))))

(def opened
  (->> current-file-path
       (m/<$> (complement empty?))
       (frp/stepper false)))

(def initial-cursor
  0)

(defn get-cursor-event
  [plus minus move]
  (->> (m/<> (aid/<$ (aid/if-then pos?
                                  dec)
                     minus)
             (aid/<$ inc plus)
             (m/<$> constantly move))
       (frp/accum initial-cursor)))

(def get-cursor-behavior
  (partial frp/stepper initial-cursor))

(def cursor-x-event
  (->> loop-file
       (m/<$> :x)
       (get-cursor-event right left)))

(def cursor-y-event
  (->> loop-file
       (m/<$> :y)
       (get-cursor-event down up)))

(def cursor-x-behavior
  (get-cursor-behavior cursor-x-event))

(def cursor-y-behavior
  (get-cursor-behavior cursor-y-event))

(defrecord Table
  [x-y y-x])

(def initial-table
  (->Table (sorted-map) (sorted-map)))

(def initial-content
  [[{:node initial-table
     :edge (graph/digraph)}]
   []])

(def exit?
  #{"Control" "Escape"})

(def command-exit
  (->> command-keydown
       (core/filter exit?)
       (m/<> submission)))

(def llast
  (comp last
        last))

(def normal
  (->> (m/<> (aid/<$ [""] insert-normal)
             (aid/<$ ["INSERT"] insert-insert)
             editor-keydown)
       (core/partition 2 1)
       (core/filter (aid/build and
                               (comp (partial = "")
                                     ffirst)
                               (comp exit?
                                     llast)))
       (m/<> normal-escape command-exit)))

(def undo-size
  10)

(def get-error
  #(try (do (js/katex.renderToString %)
            "")
        (catch js/katex.ParseError error
          (str error))))

(def valid-expression?
  (comp empty?
        get-error))

(def valid
  (core/filter valid-expression? insert-typing))

(def typed
  (->> (m/<> cursor-x-event cursor-y-event)
       (aid/<$ false)
       (m/<> (aid/<$ true valid))
       (frp/stepper false)))

(defn make-transform-current-content
  [x y s]
  (partial s/setval*
           [:node
            (s/multi-path [:y-x
                           (s/keypath [y
                                       x])]
                          [:x-y
                           (s/keypath [x
                                       y])])
            :text]
           s))

(def action
  (->> valid
       (frp/stepper "")
       (frp/snapshot normal cursor-x-behavior cursor-y-behavior typed)
       (m/<$>
         (fn [[_ x y typed* s]]
           (aid/if-else
             (comp (aid/build =
                              (make-transform-current-content x y s)
                              identity)
                   ffirst)
             (if typed*
               (comp (partial s/transform* s/FIRST (partial take undo-size))
                     (aid/transfer* [s/FIRST s/BEFORE-ELEM]
                                    (comp (make-transform-current-content x
                                                                          y
                                                                          s)
                                          ffirst)))
               identity))))
       (m/<> (m/<$> (comp constantly
                          :content)
                    loop-file))))

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
                   (aid/<$ (aid/if-then (comp not-empty
                                              last)
                                        (comp (partial s/transform* s/LAST rest)
                                              (aid/transfer* [s/FIRST
                                                              s/BEFORE-ELEM]
                                                             lfirst)))
                           redo))))

(def get-current-node*
  (comp :node
        ffirst))

(def current-node
  (m/<$> get-current-node* content))

(def current-x-y*
  (->> (frp/snapshot valid
                     cursor-x-behavior
                     cursor-y-behavior)
       (m/<$> (fn [[s x y]]
                (partial s/setval* (s/keypath [x y]) s)))
       (m/<> (m/<$> (comp constantly
                          (partial s/transform* s/MAP-VALS :text)
                          :x-y)
                    current-node))
       (frp/accum {})
       (frp/stepper {})))

(def insert-text
  (->> insert-typing
       (m/<> (m/<$> (fn [[x y m]]
                      (get m [x y] ""))
                    (frp/snapshot cursor-x-event
                                  cursor-y-behavior
                                  current-x-y*))
             (m/<$> (fn [[y x m]]
                      (get m [x y] ""))
                    (frp/snapshot cursor-y-event
                                  cursor-x-behavior
                                  current-x-y*))
             (m/<$> #(-> %
                         :content
                         get-current-node*
                         :x-y
                         (get-in [[(:x %) (:y %)] :text] ""))
                    loop-file))
       (frp/stepper "")))

(defn get-maximum
  [k b]
  (->> current-node
       (m/<$> (comp #(case %
                       {} 0
                       (-> %
                           lfirst
                           first))
                    k))
       (frp/stepper 0)
       ((aid/lift-a (comp inc
                          max))
         b)))

(def maximum-x
  (get-maximum :x-y cursor-x-behavior))

(def maximum-y
  (get-maximum :y-x cursor-y-behavior))

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
                             (aid/<$ :insert (m/<> insert-normal insert-insert))
                             (aid/<$ :command command))))

(def editor-command
  (->> editor-keyup
       (core/filter (partial = ":"))
       (aid/<$ false)
       (m/<> (m/<$> (comp (partial = ":")
                          last)
                    editor-keydown))
       (frp/stepper false)))

(def file-entry
  (frp/snapshot (->> current-file-path
                     (core/partition 2 1)
                     (m/<$> first))
                ((aid/lift-a (comp (partial zipmap [:content :x :y])
                                   vector))
                  (frp/stepper initial-content content)
                  cursor-x-behavior
                  cursor-y-behavior)))

(def file
  (->> file-entry
       (m/<$> (partial apply hash-map))
       core/merge
       (frp/stepper {})))

(def modification
  (core/remove (fn [[path* m]]
                 (and (fs/fexists? path*)
                      (-> path*
                          slurp
                          read-file
                          (= m))))
               file-entry))

(def initial-file
  {:content initial-content
   :x       initial-cursor
   :y       initial-cursor})

(def current-file
  (m/<$> (fn [[k m]]
           (get m k (aid/casep k
                      fs/fexists? (-> k
                                      slurp
                                      read-file)
                      initial-file)))
         (frp/snapshot current-file-path file)))

(defn get-scroll
  [k e]
  (->> dom
       (m/<$> k)
       (frp/stepper 0)
       (frp/snapshot e)
       (core/reduce (fn [reduction [x view-size]]
                      (-> reduction
                          (max (-> x
                                   inc
                                   (* cursor-size)
                                   (- view-size)))
                          (min (* x cursor-size))))
                    0)
       (frp/stepper 0)))

(def scroll-x
  (get-scroll :client-width cursor-x-event))

(def scroll-y
  (get-scroll :client-height cursor-y-event))

(aid/defcurried effect
  [f x]
  (f x)
  x)

(defn memoize-one
  [f!]
  ;TODO use core.memoize when core.memoize supports ClojureScript
  (let [state (atom {})]
    (fn [& more]
      (aid/case-eval more
        (:arguments @state) (:return @state)
        (->> more
             (apply f!)
             (effect #(reset! state {:arguments more
                                     :return    %})))))))

(defc editor
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

(defc math
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

(defc input-component
      [_]
      (r/create-class
        {:component-did-update #(-> %
                                    r/dom-node
                                    .focus)
         :reagent-render       (fn [s]
                                 [:input
                                  {:on-change   #(-> %
                                                     .-target.value
                                                     command-typing)
                                   :on-key-down #(-> %
                                                     .-key
                                                     command-keydown)
                                   :style       (s/setval
                                                  :width
                                                  (get-percent left-pane)
                                                  message-style)
                                   :value       s}])}))

(defc command-component
      [mode* command-text*]
      [:form {:style     {:display (case mode*
                                     :command "block"
                                     "none")}
              :on-submit #(submission command-text*)}
       [input-component command-text*]])

(def memoized-keyword
  (memoize cuerdas/keyword))

(defn convert-keys
  [x ks]
  (->> ks
       (mapcat (juxt memoized-keyword
                     #(case (-> x
                                (oget+ %)
                                goog/typeOf)
                        "function" (partial js-invoke x %)
                        (oget+ x %))))
       (apply hash-map)))

(def convert-object
  (aid/build convert-keys
             identity
             object/getKeys))

(def dom*
  (comp dom
        convert-object
        r/dom-node))

(defc graph-component
      [& _]
      (let [state (atom {})]
        (r/create-class
          {:component-did-mount  dom*
           :component-did-update (fn [this]
                                   (dom* this)
                                   (-> this
                                       r/dom-node
                                       (.scrollTo (:x @state)
                                                  (:y @state))))
           :reagent-render       (fn [scroll-x*
                                      scroll-y*
                                      maximum-x
                                      maximum-y
                                      current-x-y*
                                      cursor-x
                                      cursor-y]
                                   (swap! state (partial (aid/flip merge)
                                                         {:x scroll-x*
                                                          :y scroll-y*}))
                                   [:div {:style {:overflow "scroll"
                                                  :height   "100%"
                                                  :width    "100%"}}
                                    (->>
                                      [:svg {:style {:height (* maximum-y
                                                                cursor-size)
                                                     :width  (* maximum-x
                                                                cursor-size)}}
                                       [:rect {:height cursor-size
                                               :stroke "red"
                                               :width  cursor-size
                                               :x      (* cursor-x
                                                          cursor-size)
                                               :y      (* cursor-y
                                                          cursor-size)}]]
                                      (s/setval s/END
                                                (mapv (comp vec
                                                            (partial cons
                                                                     math-node))
                                                      current-x-y*))
                                      r/as-element)])})))

(defc error-component
      [error* editor-command*]
      [:div {:style (merge message-style {:display (if (or (empty? error*)
                                                           editor-command*)
                                                     "none"
                                                     "block")
                                          :width   (get-percent right-pane)})}
       error*])

(defc app-component
      [opened* graph-view* command-view* editor-view* error-view*]
      (s/setval s/BEGINNING
                [:div {:style {:background-color background-color
                               :color            "white"
                               :display          "flex"
                               :height           "100%"
                               :overflow         "hidden"
                               :width            "100%"}}]
                (if opened*
                  [[:div {:style {:width (get-percent left-pane)}}
                    graph-view*
                    command-view*]
                   [:div {:style {:width (get-percent right-pane)}}
                    editor-view*
                    error-view*]]
                  [command-view*])))

(def graph-view
  ((aid/lift-a graph-component)
    scroll-x
    scroll-y
    maximum-x
    maximum-y
    current-x-y*
    cursor-x-behavior
    cursor-y-behavior))

(def command-view
  ((aid/lift-a command-component) mode command-text))

(def editor-view
  ((aid/lift-a editor) mode insert-text))

(def error-view
  ((aid/lift-a error-component) error editor-command))

(def app-view
  ((aid/lift-a app-component)
    opened
    graph-view
    command-view
    editor-view
    error-view))

;TODO don't use two events when ClojureScript supports lazy evaluation
(def loop-event
  (partial run! (partial apply frp/run)))

(loop-event {loop-directory-event current-directory-path
             loop-file-path       current-file-path
             loop-file            current-file})

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app"))
         app-view)

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
   "i"      insert-insert
   "j"      down
   "k"      up
   "l"      right
   "space"  insert-normal
   "u"      undo})

(bind-keymap keymap)

(.config.loadModule ace
                    "ace/keyboard/vim"
                    #(run! (partial apply (.-CodeMirror.Vim.map %))
                           (combo/cartesian-product ["jk" "kj"]
                                                    ["<Esc>"]
                                                    ["insert" "command"])))

(frp/activate)

(run! submission config-commands)

(frp/run (partial apply spit) modification)

(.ipcRenderer.on helpers/electron helpers/channel (comp potential-file-path
                                                        last
                                                        vector))
