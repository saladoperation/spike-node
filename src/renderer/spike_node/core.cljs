(ns spike-node.core
  (:require [cats.core :as m]
            [aid.core :as aid]
            [cljsjs.mousetrap]
            [com.rpl.specter :as s]
            [frp.core :as frp]
            [nano-id.core :refer [nano-id]]))

(def new
  (keyword (nano-id)))

(frp/defe file-event up down)

(def file-behavior
  (->> file-event
       (m/<$> keyword)
       (frp/stepper new)))

(def grid-x
  (->> file-behavior
       (frp/snapshot (m/<> (aid/<$ (aid/if-then pos?
                                                dec)
                                   up)
                           (aid/<$ inc down)))
       (m/<$> (fn [[f k]] (partial s/transform* k f)))
       (frp/accum {new 0})))

(js/Mousetrap.bind "j" #(down))

(js/Mousetrap.bind "k" #(up))

(frp/activate)
