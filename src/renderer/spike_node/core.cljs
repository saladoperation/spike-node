(ns spike-node.core
  (:require [clojure.string :as str]
            [ace-editor]
            [aid.core :as aid]
            [cats.core :as m]
            [cljsjs.mousetrap]
            [com.rpl.specter :as s]
            [frp.clojure.core :as core]
            [frp.core :as frp]
            [frp.window :as window]
            [katex]
            [loom.graph :as graph]
            [nano-id.core :refer [nano-id]]
            [reagent.core :as r]))

(def new
  (keyword (nano-id)))

(frp/defe file-event
          down
          up
          left
          right
          escape
          insert
          command
          editor-keydown
          command-keydown
          insert-typing
          command-typing
          submission
          undo
          redo)

(def file-behavior
  (->> file-event
       (m/<$> keyword)
       (frp/stepper new)))

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

(def normal
  (->> insert
       (m/<$> vector)
       (m/<> editor-keydown)
       (core/partition 2 1)
       (core/filter (aid/build and
                               (comp (partial = "")
                                     ffirst)
                               (comp escape?
                                     last
                                     last)))
       (m/<> escape (core/filter escape? command-keydown))))

(def undo-size
  10)

(defn get-error
  [s]
  (try (do (js/katex.renderToString s)
           "")
       (catch js/katex.ParseError error
         (str error))))

(def valid?
  (comp empty?
        get-error))

(def valid
  (core/filter valid? insert-typing))

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
  (frp/stepper "" command-typing))

(def font-size
  18)

(def cursor-size
  (* font-size 3))

(def mode
  (frp/stepper :normal (m/<> (aid/<$ :normal normal)
                             (aid/<$ :insert insert)
                             (aid/<$ :command command))))

(defn editor
  [mode* text*]
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
                               (.-key %)]))))
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
          {:focus           (= :insert mode*)
           :keyboardHandler "vim"
           :mode            "latex"
           :value           text*
           :onChange        #(insert-typing %)
           :onFocus         #(insert
                               (.keyBinding.getStatusText (:editor @state)
                                                          (:editor @state)))
           :ref             #(if %
                               (swap! state
                                      (partial s/setval* :editor (.-editor %))))
           :style           {:font-size font-size
                             :height    "100%"
                             :width     "100%"}
           :theme           "terminal"}])})))

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

(defn command-component
  [s]
  (r/create-class
    {:component-did-update (fn [this]
                             (.focus (r/dom-node this)))
     :reagent-render       (fn [s]
                             [:input
                              {:on-change   #(-> %
                                                 .-target.value
                                                 command-typing)
                               :on-key-down #(-> %
                                                 .-key
                                                 command-keydown)
                               :style       {:background-color background-color
                                             :border           "none"
                                             :color            "white"
                                             :width            "100%"}
                               :value       s}])}))

(defn app-component
  [cursor-x* cursor-y* mode* current-node* insert-text* command-text* error*]
  [:div {:style {:background-color background-color
                 :color            "white"
                 :display          "flex"
                 :height           "100%"
                 :overflow         "hidden"
                 :width            "100%"}}
   (s/setval s/END
             (->> current-node*
                  (mapv (fn [[position text**]]
                          [math-node position text**])))
             [:svg {:style {:width "50%"}}
              [:rect {:height cursor-size
                      :stroke "red"
                      :width  cursor-size
                      :x      (* cursor-x* cursor-size)
                      :y      (* cursor-y* cursor-size)}]])
   [:div {:style {:display        "flex"
                  :flex-direction "column"
                  :width          "50%"}}
    [editor mode* insert-text*]
    [:div {:style {:background-color background-color
                   :display          (if (and (not= mode* :command)
                                              (empty? error*))
                                       "none"
                                       "block")
                   :height           font-size}}
     (case error*
       "" [:form {:on-submit #(submission command-text*)}
           [command-component command-text*]]
       error*)]]])

(def app
  ((aid/lift-a app-component)
    x-behavior
    y-behavior
    mode
    current-node
    insert-text
    command-text
    error))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app")) app)

(defn bind
  [s e]
  (js/Mousetrap.bind s #(e)))

(def bind-keymap
  (partial run! (partial apply bind)))

(def keymap
  {":"      command
   "escape" escape
   "h"      left
   "i"      insert
   "j"      down
   "k"      up
   "l"      right
   "r"      redo
   "space"  insert
   "u"      undo})

(bind-keymap keymap)

(frp/run (comp aid/funcall
               :prevent-default)
         window/submit)

(frp/activate)
