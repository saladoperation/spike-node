(ns spike-node.core
  (:require [aid.core :as aid]
            [cats.core :as m]
            [cljsjs.mousetrap]
            [com.rpl.specter :as s]
            [frp.core :as frp]
            [nano-id.core :refer [nano-id]]
            [reagent.core :as r]))

(def new
  (keyword (nano-id)))

(frp/defe file-event up down)

(def file-behavior
  (->> file-event
       (m/<$> keyword)
       (frp/stepper new)))

(def file-y
  (->> file-behavior
       (frp/snapshot (m/<> (aid/<$ (aid/if-then pos?
                                                dec)
                                   up)
                           (aid/<$ inc down)))
       (m/<$> (fn [[f k]]
                (partial s/transform* k f)))
       (frp/accum {new 0})))

(def active-y
  ((aid/lift-a aid/funcall)
    file-behavior
    (frp/stepper {new 0} file-y)))

(def size
  32)

(defn app-component
  [active-y*]
  [:svg {:style {:background-color "black"
                 :height           "100%"
                 :width            "100%"}}
   [:rect {:y      (* active-y* size)
           :width  size
           :height size
           :stroke "white"}]])

(def app
  (m/<$> app-component active-y))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app")) app)

(js/Mousetrap.bind "j" #(down))

(js/Mousetrap.bind "k" #(up))

(frp/activate)
