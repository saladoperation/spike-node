(ns spike-node.core
  (:require [ace-editor]
            [aid.core :as aid]
            [cats.core :as m]
            [cljsjs.mousetrap]
            [frp.clojure.core :as core]
            [frp.core :as frp]
            [nano-id.core :refer [nano-id]]
            [reagent.core :as r]))

(def new
  (keyword (nano-id)))

(frp/defe file-event up down insert keydown status text)

(def file-behavior
  (->> file-event
       (m/<$> keyword)
       (frp/stepper new)))

(def cursor-y
  (->> (m/<> (aid/<$ (aid/if-then pos?
                                  dec)
                     up)
             (aid/<$ inc down))
       (frp/accum 0)
       (frp/stepper 0)))

(def size
  16)

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
                             :height    "20%"}
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
  [cursor-y* mode*]
  [:div {:style {:background-color "black"
                 :height           "100%"
                 :width            "100%"}}
   [:svg {:style {:height "80%"}}
    [:rect {:height size
            :stroke "white"
            :width  size
            :y      (* cursor-y* size)}]]
   [editor mode*]])

(def app
  ((aid/lift-a app-component) cursor-y mode))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app")) app)

(js/Mousetrap.bind "j" #(down))

(js/Mousetrap.bind "k" #(up))

(js/Mousetrap.bind "i" #(insert))

(frp/activate)
