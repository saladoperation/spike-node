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

(frp/defe file-event down up left right insert keydown status typing undo redo)

(def file-behavior
  (->> file-event
       (m/<$> keyword)
       (frp/stepper new)))

(defn get-cursor
  [plus minus]
  (->> (m/<> (aid/<$ (aid/if-then pos?
                                  dec)
                     minus)
             (aid/<$ inc plus))
       ;TODO extract a function
       (frp/accum 0)
       (frp/stepper 0)))

(def cursor-x
  (get-cursor right left))

(def cursor-y
  (get-cursor down up))

(def initial-table
  {:y-x (sorted-map)
   :x-y (sorted-map)})

(def initial-content
  [[{:node initial-table
     :edge (graph/digraph)}]
   []])

(def normal
  (->> status
       (frp/stepper "")
       (frp/snapshot keydown)
       (core/partition 2 1)
       (core/filter (aid/build and
                               (comp (partial = "Escape")
                                     first
                                     last)
                               (comp (partial = "")
                                     last
                                     first)))))

(def action
  (->> typing
       (frp/stepper "")
       (frp/snapshot normal cursor-x cursor-y)
       (m/<$> (fn [[_ x y s]]
                (comp (partial s/setval*
                               [s/FIRST
                                s/FIRST
                                :node
                                (s/multi-path [:y-x (s/keypath [y x])]
                                              [:x-y (s/keypath [x y])])
                                :text]
                               s)
                      (aid/transfer* [s/FIRST s/BEFORE-ELEM]
                                     ffirst))))))

(def content
  ;TODO implement undo and redo
  (frp/accum initial-content (m/<> action undo redo)))

(def current-node
  (->> (m/<> (m/<$> (fn [[s x y]]
                      (partial s/setval* (s/keypath [x y]) s))
                    (frp/snapshot typing cursor-x cursor-y))
             (m/<$> (comp constantly
                          (partial s/transform* s/MAP-VALS :text)
                          :x-y
                          :node
                          ffirst)
                    content))
       (frp/accum {})
       (frp/stepper {})))

(def text
  ((aid/lift-a (fn [x y m]
                 (get m [x y] "")))
    cursor-x
    cursor-y
    current-node))

(def font-size
  18)

(def size
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
         (.on (:editor @state)
              "changeStatus"
              #(status (.keyBinding.getStatusText (:editor @state)
                                                  (:editor @state))))
         (-> @state
             :editor
             .textInput.getElement
             (.addEventListener "keydown"
                                #(-> %
                                     .-key
                                     keydown))))
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
           :onFocus         #(insert)
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
  [:foreignObject {:x (* x size)
                   :y (* y size)}
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
             [:svg {:style {:height "80%"}}
              [:rect {:height size
                      :stroke "white"
                      :width  size
                      :x      (* cursor-x* size)
                      :y      (* cursor-y* size)}]])
   [editor mode* text*]])

(def app
  ((aid/lift-a app-component) cursor-x cursor-y mode current-node text))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app")) app)

(js/Mousetrap.bind "j" #(down))

(js/Mousetrap.bind "k" #(up))

(js/Mousetrap.bind "h" #(left))

(js/Mousetrap.bind "l" #(right))

(js/Mousetrap.bind "i" #(insert))

(frp/activate)
