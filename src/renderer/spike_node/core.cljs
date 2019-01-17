(ns spike-node.core
  (:require [ace-editor]
            [aid.core :as aid]
            [cats.core :as m]
            [cljsjs.mousetrap]
            [com.rpl.specter :as s]
            [frp.clojure.core :as core]
            [frp.core :as frp]
            [loom.graph :as graph]
            [nano-id.core :refer [nano-id]]
            [reagent.core :as r]))

(def new
  (keyword (nano-id)))

(frp/defe file-event down up left right insert keydown status text undo redo)

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
       (frp/accum 0)
       (frp/stepper 0)))

(def cursor-x
  (get-cursor right left))

(def cursor-y
  (get-cursor down up))

(def initial-table
  {:pair (sorted-map)
   :x-y  {}
   :y-x  {}})

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
  (m/<$> (fn [[_ x y s]]
           (comp (partial s/setval*
                          [s/FIRST
                           s/FIRST
                           :node
                           ;TODO set :x-y and :y-x
                           (s/multi-path [:pair (s/keypath [y x])])
                           :text]
                          s)
                 (aid/transfer* [s/FIRST s/BEFORE-ELEM]
                                ffirst)))
         (frp/snapshot normal cursor-x cursor-y (frp/stepper "" text))))

(def content
  ;TODO implement undo and redo
  (frp/accum initial-content (m/<> action undo redo)))

(def size
  16)

(def mode
  (->> insert
       (aid/<$ :insert)
       (m/<> (aid/<$ :normal normal))
       (frp/stepper :normal)))

(defn editor
  [mode*]
  (let [editor-state (atom {})]
    [(with-meta
       (fn [mode**]
         [:> ace-editor
          {:focus           (= :insert mode**)
           :keyboardHandler "vim"
           :mode            "latex"
           :onChange        #(text %)
           :onFocus         #(insert)
           :ref             #(if %
                               (->> %
                                    .-editor
                                    (reset! editor-state)))
           :style           {:font-size size
                             :height    "20%"
                             :width     "100%"}
           :theme           "terminal"}])
       {:component-did-mount
        (fn []
          (.on @editor-state
               "changeStatus"
               #(status (.keyBinding.getStatusText @editor-state
                                                   @editor-state)))
          (-> @editor-state
              .textInput.getElement
              (.addEventListener "keydown"
                                 #(-> %
                                      .-key
                                      keydown))))})
     mode*]))

(defn app-component
  [cursor-x* cursor-y* mode*]
  [:div {:style {:background-color "black"
                 :height           "100%"
                 :width            "100%"}}
   [:svg {:style {:height "80%"}}
    [:rect {:height size
            :stroke "white"
            :width  size
            :x      (* cursor-x* size)
            :y      (* cursor-y* size)}]]
   [editor mode*]])

(def app
  ((aid/lift-a app-component) cursor-x cursor-y mode))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app")) app)

(js/Mousetrap.bind "j" #(down))

(js/Mousetrap.bind "k" #(up))

(js/Mousetrap.bind "h" #(left))

(js/Mousetrap.bind "l" #(right))

(js/Mousetrap.bind "i" #(insert))

(frp/activate)
