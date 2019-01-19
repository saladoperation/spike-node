(ns spike-node.core
  (:require [clojure.string :as str]
            [ace-editor]
            [aid.core :as aid]
            [cats.core :as m]
            [cljsjs.mousetrap]
            [com.rpl.specter :as s]
            [frp.clojure.core :as core]
            [frp.core :as frp]
            [katex]
            [loom.graph :as graph]
            [nano-id.core :refer [nano-id]]
            [reagent.core :as r]))

(def new
  (keyword (nano-id)))

(frp/defe file-event down up left right insert keydown typing undo redo)

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

(def normal
  (->> insert
       (m/<$> vector)
       (m/<> keydown)
       (core/partition 2 1)
       (core/filter (aid/build and
                               (comp (partial = "")
                                     ffirst)
                               (comp (partial = "Escape")
                                     last
                                     last)))))

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
  (core/filter valid? typing))

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

(def text
  (->> typing
       (m/<> (m/<$> (fn [[x y m]]
                      (get m [x y] ""))
                    (frp/snapshot x-event y-behavior current-node))
             (m/<$> (fn [[y x m]]
                      (get m [x y] ""))
                    (frp/snapshot y-event x-behavior current-node)))
       (frp/stepper "")))

(def font-size
  18)

(def cursor-size
  (* font-size 3))

(def mode
  (->> insert
       (aid/<$ :insert)
       (m/<> (aid/<$ :normal normal))
       (frp/stepper :normal)))

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
             #(keydown [(.keyBinding.getStatusText (:editor @state)
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
           :onChange        #(typing %)
           :onFocus         #(insert
                               (.keyBinding.getStatusText (:editor @state)
                                                          (:editor @state)))
           :ref             #(if %
                               (swap! state
                                      (partial s/setval* :editor (.-editor %))))
           :style           {:font-size font-size
                             :height    "20%"
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

(defn app-component
  [cursor-x* cursor-y* mode* current-node* text*]
  [:div {:style {:background-color "black"
                 :color            "white"
                 :height           "100%"
                 :width            "100%"}}
   (s/setval s/END
             (->> current-node*
                  (mapv (fn [[position text**]]
                          [math-node position text**])))
             [:svg {:style {:height "80%"
                            :width  "100%"}}
              [:rect {:height cursor-size
                      :stroke "red"
                      :width  cursor-size
                      :x      (* cursor-x* cursor-size)
                      :y      (* cursor-y* cursor-size)}]])
   [:div {:style {:top      "75%"
                  :height   "5%"
                  :position "absolute"
                  :width    "100%"}}
    [:div {:style {:bottom   0
                   :position "absolute"}}
     (get-error text*)]]
   [editor mode* text*]])

(def app
  ((aid/lift-a app-component) x-behavior y-behavior mode current-node text))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app")) app)

(defn bind
  [s e]
  (js/Mousetrap.bind s #(e)))

(def bind-keymap
  (partial run! (partial apply bind)))

(def keymap
  {"h"     left
   "i"     insert
   "j"     down
   "k"     up
   "l"     right
   "r"     redo
   "space" insert
   "u"     undo})

(bind-keymap keymap)

(frp/activate)
