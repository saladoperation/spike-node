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
            katex
            measure
            [loom.graph :as graph]
            [oops.core :refer [oget+ oset!]]
            [reagent.core :as r]
            [spike-node.helpers :as helpers]
            [spike-node.loom :as loom]
            [spike-node.parse.core :as parse])
  (:require-macros [spike-node.core :refer [defc]]))

(frp/defe source-directory
          source-file
          source-scroll-x
          source-scroll-y
          down
          up
          left
          right
          carrot
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
          bounds
          implication
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

(def get-potential-path
  #(->>
     source-directory
     (frp/stepper default-path)
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
                               (partial into (sorted-map)))
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

(def sink-directory
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
  (->> source-file
       (m/<$> :x)
       (m/<> (aid/<$ initial-cursor carrot))
       (get-cursor-event right left)))

(def cursor-y-event
  (->> source-file
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
       (m/<> command-exit normal-escape)))

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

(defn make-transform-node
  [x y s]
  (partial s/setval*
           [:node
            (s/multi-path [:y-x
                           (s/keypath [y
                                       x])]
                          [:x-y
                           (s/keypath [x
                                       y])])]
           (aid/casep s
             empty? s/NONE
             s)))

(def action
  (->> (frp/snapshot (->> (frp/snapshot normal typed)
                          (core/filter last)
                          (m/<$> first))
                     ((aid/lift-a make-transform-node)
                       cursor-x-behavior
                       cursor-y-behavior
                       (frp/stepper "" valid)))
       (m/<$> (fn [[_ f]]
                (aid/if-else (comp (aid/build =
                                              f
                                              identity)
                                   ffirst)
                             (comp (partial s/transform*
                                            s/FIRST
                                            (partial take undo-size))
                                   (aid/transfer* [s/FIRST s/BEFORE-ELEM]
                                                  (comp f
                                                        ffirst))))))
       (m/<> (m/<$> (comp constantly
                          :content)
                    source-file))))

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

(def node
  (m/<$> (comp :node
               ffirst)
         content))

(def x-y-event
  (->> (frp/snapshot valid
                     cursor-x-behavior
                     cursor-y-behavior)
       (m/<$> (fn [[s x y]]
                (partial s/setval* (s/keypath [x y]) s)))
       (m/<> (m/<$> (comp constantly
                          :x-y)
                    node))
       (frp/accum {})))

(def x-y-behavior
  (frp/stepper {} x-y-event))

(aid/defcurried extract-insert
  [n coll]
  (->> coll
       (s/setval s/FIRST s/NONE)
       (s/setval (s/before-index n) (first coll))))

(defn zip-entities
  [f es bs]
  (->> es
       (map-indexed (fn [n e]
                      (->> bs
                           (s/setval (s/nthpath n) s/NONE)
                           (apply frp/snapshot e)
                           (m/<$> (comp f
                                        (extract-insert n))))))
       (apply m/<>)))

(def insert-text
  (->> insert-typing
       (m/<> (zip-entities (fn [[x y m]]
                             (get m [x y] ""))
                           [cursor-x-event cursor-y-event x-y-event]
                           [cursor-x-behavior
                            cursor-y-behavior
                            x-y-behavior]))
       (frp/stepper "")))

(def edge-node
  (->> (frp/snapshot implication
                     insert-text
                     cursor-x-behavior
                     cursor-y-behavior)
       (core/filter (comp (partial not= "")
                          second))
       (m/<$> (partial drop 2))))

(defn add-scroll
  [k0 k1 scroll bound]
  (s/transform (s/multi-path k0 k1) (partial + scroll) bound))

(def valid-bounds
  (->> (frp/snapshot bounds
                     (frp/stepper 0 source-scroll-x)
                     (frp/stepper 0 source-scroll-y))
       (m/<$> (comp (aid/build hash-map
                               (juxt :left :top)
                               identity)
                    (fn [[bound scroll-x scroll-y]]
                      (->> bound
                           (add-scroll :left :right scroll-x)
                           (add-scroll :top :bottom scroll-y)))))
       (core/reduce merge {})
       (m/<$> (comp (partial remove (comp zero?
                                          :width))
                    vals))))

(def error
  (m/<$> get-error insert-text))

(def command-text
  (->> command-exit
       (aid/<$ "")
       (m/<> command-typing)
       (frp/stepper "")))

(def mode
  (frp/stepper :normal (m/<> (aid/<$ :normal normal)
                             (aid/<$ :insert (m/<> insert-normal insert-insert))
                             (aid/<$ :command command)
                             (aid/<$ :edge edge-node))))

(def in
  (->> mode
       (frp/snapshot (core/distinct edge-node))
       (core/filter (comp (partial = :edge)
                          last))
       (m/<$> first)))

(def placeholder
  [])

(def edge
  (frp/snapshot in (frp/stepper placeholder edge-node)))

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

(def sink-file
  (m/<$> (fn [[k m]]
           (get m k (aid/casep k
                      fs/fexists? (-> k
                                      slurp
                                      read-file)
                      initial-file)))
         (frp/snapshot current-file-path file)))

(def initial-scroll
  0)

(def font-size
  18)

(def cursor-size
  (* font-size 3))

(def initial-maximum
  0)

(defn get-scroll
  [client bound cursor]
  (->> client
       (frp/snapshot (m/<> bound cursor))
       (core/reduce (fn [reduction [x view-size]]
                      (-> reduction
                          (max (-> x
                                   inc
                                   (* cursor-size)
                                   (- view-size)))
                          (min (* x cursor-size))))
                    initial-scroll)
       (frp/stepper initial-scroll)))

(def maximum-pixel
  ;https://stackoverflow.com/a/16637689
  33554428)

(def client-width
  (->> dom
       (m/<$> :client-width)
       (frp/stepper maximum-pixel)))

(def client-height
  (->> dom
       (m/<$> :client-height)
       (frp/stepper maximum-pixel)))

(def get-maximum-bound
  #(m/<$> (comp (partial (aid/flip quot) cursor-size)
                (partial apply max initial-maximum)
                (partial map %))
          valid-bounds))

(def maximum-x-bound
  (get-maximum-bound :right))

(def maximum-y-bound
  (get-maximum-bound :bottom))

(def sink-scroll-x
  (get-scroll client-width maximum-x-bound cursor-x-event))

(def sink-scroll-y
  (get-scroll client-height maximum-y-bound cursor-y-event))

(def get-pixel
  (partial m/<$> (comp (partial * cursor-size)
                       inc)))

(defn get-maximum
  [client scroll bound cursor]
  ((aid/lift-a max)
    ((aid/lift-a +) client scroll)
    (get-pixel (frp/stepper initial-maximum bound))
    (get-pixel cursor)))

(def maximum-x
  (get-maximum client-width
               sink-scroll-x
               maximum-x-bound
               cursor-x-behavior))

(def maximum-y
  (get-maximum client-height
               sink-scroll-y
               maximum-y-bound
               cursor-y-behavior))

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

(defn math
  [s]
  [:div
   {:dangerouslySetInnerHTML
    {:__html (js/katex.renderToString s
                                      #js {:displayMode true})}}])

(def align
  (partial (aid/flip str/join) ["\\begin{aligned}" "\\end{aligned}"]))

(defn math-node
  [[[x y] s]]
  [:> measure
   {:bounds    true
    :on-resize #(-> %
                    .-bounds
                    (js->clj :keywordize-keys true)
                    bounds)}
   #(r/as-element [:foreignObject {:x (* x cursor-size)
                                   :y (* y cursor-size)}
                   [:div {:ref   (.-measureRef %)
                          :style {:display "inline-block"}}
                    [math (align s)]]])])

(def background-color
  "black")

(def maximum-z-index
  ;https://stackoverflow.com/a/25461690
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

(aid/defcurried convert-keys
  [ks x]
  (->> ks
       (mapcat (juxt memoized-keyword
                     #(case (-> x
                                (oget+ %)
                                goog/typeOf)
                        "function" (partial js-invoke x %)
                        (oget+ x %))))
       (apply hash-map)))

;(def convert-object
;  (aid/build convert-keys
;             object/getKeys
;             identity))
;
;(def dom*
;  (comp dom
;        convert-object
;        r/dom-node))
;
;=> @dom
;#object[TypeError TypeError: Cannot convert a Symbol value to a string]
(def dom*
  (comp dom
        (convert-keys #{"clientWidth"
                        "clientHeight"})
        r/dom-node))

(defc nodes
      [x-y*]
      (->> x-y*
           (mapv math-node)
           (s/setval s/BEFORE-ELEM :g)))

(def outline-width
  1)

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
                                      x-y*
                                      cursor-x
                                      cursor-y]
                                   (swap! state (partial (aid/flip merge)
                                                         {:x scroll-x*
                                                          :y scroll-y*}))
                                   [:div {:style {:overflow "scroll"
                                                  :height   "100%"
                                                  :width    "100%"}}
                                    [:svg {:style {:height maximum-y
                                                   :width  maximum-x}}
                                     [:rect {:height cursor-size
                                             :style  {:outline-color  "red"
                                                      :outline-offset (- outline-width)
                                                      :outline-style  "solid"
                                                      :outline-width  outline-width}
                                             :width  cursor-size
                                             :x      (* cursor-x cursor-size)
                                             :y      (* cursor-y cursor-size)}]
                                     [nodes x-y*]]])})))

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
    sink-scroll-x
    sink-scroll-y
    maximum-x
    maximum-y
    x-y-behavior
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

(loop-event {source-directory sink-directory
             source-file      sink-file
             source-scroll-x  sink-scroll-x
             source-scroll-y  sink-scroll-y})

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app"))
         app-view)

(oset! js/window "onsubmit" #(.preventDefault %))

(defn bind
  [s e]
  (js/Mousetrap.bind s #(e)))

(def bind-keymap
  (partial run! (partial apply bind)))

(def keymap
  {":"      command
   "^"      carrot
   "\\"     implication
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
