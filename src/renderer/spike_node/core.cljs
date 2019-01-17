(ns spike-node.core
  (:require [aid.core :as aid]
            [cats.core :as m]
            [cljsjs.mousetrap]
            [frp.core :as frp]
            [nano-id.core :refer [nano-id]]
            [reagent.core :as r]))

(def new
  (keyword (nano-id)))

(frp/defe file-event up down insert)

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
  32)

(defn app-component
  [cursor-y* mode*]
  [:div {:style {:background-color "black"
                 :height           "100%"
                 :width            "100%"}}
   [:svg {:style {:height "80%"}}
    [:rect {:y      (* cursor-y* size)
            :width  size
            :height size
            :stroke "white"}]]])

(def mode
  (frp/stepper :normal (aid/<$ :insert insert)))

(def app
  ((aid/lift-a app-component) cursor-y mode))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app")) app)

(js/Mousetrap.bind "j" #(down))

(js/Mousetrap.bind "k" #(up))

(js/Mousetrap.bind "i" #(insert))

(frp/activate)
