(ns spike-node.core
  (:require [cljsjs.mousetrap]
            [frp.core :as frp]))

(frp/defe up down)

(js/Mousetrap.bind "j" #(down))

(js/Mousetrap.bind "k" #(up))

(frp/activate)
