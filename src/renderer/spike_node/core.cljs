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
            [thi.ng.geom.line :as line]
            [thi.ng.geom.rect :as rect]
            [thi.ng.geom.core :as geom]
            [spike-node.helpers :as helpers]
            [spike-node.loom :as loom]
            [spike-node.parse.core :as parse]
            [clojure.set :as set])
  (:require-macros [spike-node.core :refer [defc]]))

(frp/defe source-buffer
          source-content
          source-directory
          source-edge-register
          source-in
          source-line-segment
          source-node-register
          source-scroll-x
          source-scroll-y
          source-transform-edge-action
          blockwise-visual-toggle
          down
          up
          left
          right
          carrot
          delete
          paste
          dom
          escape
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
  (partial
    edn/read-string
    {:readers
     {'loom.graph.BasicEditableDigraph (comp loom/digraph
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

(def get-undo-redo-cursor
  #(m/<$> last (frp/snapshot (m/<> undo redo)
                             (->> source-content
                                  (m/<$> %)
                                  (frp/stepper initial-cursor)))))

(def cursor-x-event
  (->> source-buffer
       (m/<$> :x)
       (m/<> (aid/<$ initial-cursor carrot)
             (get-undo-redo-cursor :x))
       (get-cursor-event right left)))

(def cursor-y-event
  (->> source-buffer
       (m/<$> :y)
       (m/<> (get-undo-redo-cursor :y))
       (get-cursor-event down up)))

(def cursor-x-behavior
  (get-cursor-behavior cursor-x-event))

(def cursor-y-behavior
  (get-cursor-behavior cursor-y-event))

(def initial-canonical
  {})

(def initial-node
  ;TODO add :horizontal
  {:canonical initial-canonical
   :id        {}})

(def initial-edge
  (graph/digraph))

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
       (m/<> command-exit escape)))

(def undo-size
  10)

(def align
  (partial (aid/flip str/join) ["\\begin{aligned}" "\\end{aligned}"]))

(def get-error
  #(try (do (-> %
                align
                js/katex.renderToString)
            "")
        (catch js/katex.ParseError error
          (str error))))

(def valid-expression?
  (comp empty?
        get-error))

(def valid-expression
  (core/filter valid-expression? insert-typing))

(def typed
  (->> (m/<> cursor-x-event cursor-y-event)
       (aid/<$ false)
       (m/<> (aid/<$ true valid-expression))
       (frp/stepper false)))

(defn get-none-value
  [s x]
  (aid/casep s
    empty? s/NONE
    x))

(defn get-set-node-action**
  [s x y]
  #(let [id (get (:id %)
                 [x
                  y]
                 (-> (random-uuid)
                     str
                     keyword))]
     (->> %
          (s/setval [:canonical
                     id]
                    (get-none-value s {:value s
                                       :x     x
                                       :y     y}))
          (s/setval [:id
                     (s/keypath [x
                                 y])]
                    (get-none-value s id)))))

(def get-set-action*
  (comp ((aid/curry 3 s/transform*) :node)
        get-set-node-action**))

(def marker-size
  8)

(def font-size
  (* 2 marker-size))

(def cursor-size
  (* font-size 3))

(def get-x-cursor-pixel
  (comp (partial + (/ marker-size 2))
        (partial * cursor-size)))

(def blockwise-visual-mode
  (->> (m/<> (aid/<$ not blockwise-visual-toggle)
             (aid/<$ (constantly false) (m/<> delete escape undo)))
       (frp/accum false)
       (frp/stepper false)))

(def node-placeholder
  [js/Number.POSITIVE_INFINITY js/Number.POSITIVE_INFINITY])

(def blockwise-visual-node
  (->> (frp/snapshot blockwise-visual-toggle
                     cursor-x-behavior
                     cursor-y-behavior)
       (m/<$> rest)
       (frp/stepper node-placeholder)))

(aid/defcurried between?
  [x y z]
  (<= (min x y) z (max x y)))

(def flast
  (comp last
        first))

(defn make-between?*
  [mode a0 a1]
  (between? (if mode
              a0
              a1)
            a1))

(defn make-in?
  [mode x0 x1 y0 y1]
  (aid/build and
             (comp (make-between?* mode x0 x1)
                   ffirst)
             (comp (make-between?* mode y0 y1)
                   flast)))

(defn get-id-path
  [& more]
  [:node
   :id
   s/ALL
   (->> more
        (apply make-in?)
        s/pred)
   s/LAST])

(aid/defcurried select-ids
  [mode x0 x1 y0 y1 m]
  (s/select* (get-id-path mode x0 x1 y0 y1) m))

(def get-size
  (comp (partial * cursor-size)
        inc
        Math/abs
        -))

(defn get-delete-action*
  [mode [x0 y0] x1 y1 line-segment]
  (aid/if-then-else
    (comp empty?
          (select-ids mode x0 x1 y0 y1))
    (partial
      s/transform*
      :edge
      (partial
        (aid/flip graph/remove-edges*)
        (->> line-segment
             (filter (comp (partial geom/intersect-line
                                    (rect/rect (get-x-cursor-pixel (min x0
                                                                        x1))
                                               (* (min y0 y1) cursor-size)
                                               (get-size x0 x1)
                                               (get-size y0 y1)))
                           val))
             (map first))))
    (comp #(s/setval (s/multi-path (get-id-path mode x0 x1 y0 y1)
                                   [:node
                                    :canonical
                                    (apply s/multi-path
                                           (select-ids mode x0 x1 y0 y1 %))])
                     s/NONE
                     %)
          (aid/transfer* :edge
                         (aid/build (partial apply graph/remove-nodes)
                                    :edge
                                    (select-ids mode x0 x1 y0 y1))))))

(defn offset-paste
  [k a register]
  (s/transform [s/MAP-VALS k] (partial + a) register))

(def get-coordinate-id
  (comp (partial apply
                 hash-map)
        (partial mapcat (aid/build vector
                                   (comp (juxt :x :y)
                                         last)
                                   first))))

(def augment
  (comp (partial zipmap [:canonical :id])
        (juxt identity
              get-coordinate-id)))

(defn deep-merge-with
  [f & more]
  (aid/if-then-else (partial every? map?)
                    (partial apply
                             merge-with
                             (partial deep-merge-with f))
                    (partial apply f)
                    more))

(def deep-merge
  (partial deep-merge-with (comp last
                                 vector)))


(defn get-paste-action*
  [register x y]
  (partial s/transform* :node (partial deep-merge (->> register
                                                       (offset-paste :x x)
                                                       (offset-paste :y y)
                                                       augment))))

(def graph-action
  (->> (m/<> (frp/snapshot (->> (frp/snapshot normal typed)
                                (core/filter last)
                                (m/<$> first))
                           ((aid/lift-a get-set-action*)
                             (frp/stepper "" valid-expression)
                             cursor-x-behavior
                             cursor-y-behavior))
             (frp/snapshot delete
                           ((aid/lift-a get-delete-action*)
                             blockwise-visual-mode
                             blockwise-visual-node
                             cursor-x-behavior
                             cursor-y-behavior
                             (frp/stepper {} source-line-segment)))
             (frp/snapshot paste
                           ((aid/lift-a get-paste-action*)
                             (frp/stepper {} source-node-register)
                             cursor-x-behavior
                             cursor-y-behavior)))
       (m/<$> last)
       (m/<> source-transform-edge-action)))

(def action
  (m/<$> (comp #(aid/if-else (comp (aid/build =
                                              %
                                              identity)
                                   ffirst)
                             (comp (partial s/setval* s/LAST [])
                                   (partial s/transform*
                                            s/FIRST
                                            (partial take undo-size))
                                   (aid/transfer* [s/FIRST s/BEFORE-ELEM]
                                                  (comp %
                                                        ffirst))))
               (fn [[f x y]]
                 (comp (partial s/setval* :y y)
                       (partial s/setval* :x x)
                       f)))
         (frp/snapshot graph-action cursor-x-behavior cursor-y-behavior)))

(def multiton?
  (comp (partial < 1)
        count))

(def lfirst
  (comp first
        last))

(def get-history
  (comp (partial (aid/flip vector) [])
        vector))

(def initial-history
  (get-history {:node initial-node
                :edge initial-edge
                :x    initial-cursor
                :y    initial-cursor}))

(def reset
  (m/<$> (comp constantly
               :history)
         source-buffer))

(def history
  (->> action
       (m/<> (aid/<$ (aid/if-then (comp multiton?
                                        first)
                                  (comp (partial s/transform*
                                                 s/FIRST
                                                 rest)
                                        (aid/transfer* [s/LAST
                                                        s/BEFORE-ELEM]
                                                       ffirst)))
                     undo)
             (aid/<$ (aid/if-else (comp empty?
                                        last)
                                  (comp (partial s/transform* s/LAST rest)
                                        (aid/transfer* [s/FIRST
                                                        s/BEFORE-ELEM]
                                                       lfirst)))
                     redo)
             reset)
       (frp/accum initial-history)))

(def sink-content
  (m/<$> ffirst history))

(def edge-event
  (m/<$> :edge sink-content))

(def edge-behavior
  (frp/stepper initial-edge edge-event))

(def node-event
  (->> (frp/snapshot valid-expression cursor-x-behavior cursor-y-behavior)
       (m/<$> (partial apply get-set-node-action**))
       (m/<> (m/<$> (comp constantly
                          :node)
                    sink-content))
       (frp/accum initial-node)))

(def node-behavior
  (frp/stepper initial-node node-event))

(def canonical
  (m/<$> :canonical node-behavior))

(def id
  (m/<$> :id node-behavior))

(defn make-offset-register
  [k a0 a1]
  (aid/build (partial s/transform* [s/MAP-VALS k])
             (comp (aid/flip (aid/curry 2 -))
                   (partial apply min a0 a1)
                   (partial map k)
                   vals)
             identity))

(def sink-node-register
  (m/<$> (fn [[_ m mode [x0 y0] x1 y1]]
           (->> m
                (s/setval [s/ALL
                           (-> (aid/build and
                                          (comp (make-between?* mode x0 x1)
                                                :x
                                                last)
                                          (comp (make-between?* mode y0 y1)
                                                :y
                                                last))
                               complement
                               s/pred)]
                          s/NONE)
                ((make-offset-register :x x0 x1))
                ((make-offset-register :y y0 y1))))
         (frp/snapshot delete
                       canonical
                       blockwise-visual-mode
                       blockwise-visual-node
                       cursor-x-behavior
                       cursor-y-behavior)))

(def neighbors
  (aid/build set/union
             graph/predecessors
             graph/successors))

(def get-nodes
  (aid/build conj
             neighbors
             (comp last
                   vector)))

(def sink-edge-register
  (m/<$> (fn [[node-register edge]]
           (->> node-register
                keys
                (mapcat (partial get-nodes edge))
                (graph/subgraph edge)))
         (frp/snapshot sink-node-register edge-behavior)))

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
                             (if-let [id* ((:id m) [x y])]
                               (-> m
                                   :canonical
                                   id*
                                   :value)
                               ""))
                           [cursor-x-event cursor-y-event node-event]
                           [cursor-x-behavior
                            cursor-y-behavior
                            node-behavior]))
       (frp/stepper "")))

(def edge-node-event
  (->> (frp/snapshot implication
                     insert-text
                     cursor-x-behavior
                     cursor-y-behavior
                     id)
       (core/remove (comp empty?
                          second))
       (m/<$> (comp (aid/build aid/funcall
                               last
                               (partial take 2))
                    (partial drop 2)))))

(def edge-node-behavior
  (frp/stepper node-placeholder edge-node-event))

(defn add-scroll
  [k0 k1 scroll bound]
  (s/transform (s/multi-path k0 k1) (partial + scroll) bound))

(def valid-bounds
  (->> (frp/snapshot bounds
                     (frp/stepper 0 source-scroll-x)
                     (frp/stepper 0 source-scroll-y))
       (m/<$> (comp (aid/flip (aid/curry 2 merge))
                    (aid/build hash-map
                               :id
                               identity)
                    (fn [[bound scroll-x scroll-y]]
                      (->> bound
                           (add-scroll :left :right scroll-x)
                           (add-scroll :top :bottom scroll-y)))))
       (m/<> (m/<$> (comp (aid/curry 2 s/select-one*)
                          s/submap
                          keys
                          :canonical)
                    node-event))
       (frp/accum {})
       (m/<$> (aid/if-then-else empty?
                                (constantly [])
                                vals))))

(def make-directional
  #(comp (partial apply =)
         (partial map %)))

(def horizontal?
  (make-directional :top))

(def vertical?
  (make-directional :left))

(def oblique?
  (complement (aid/build or
                         horizontal?
                         vertical?)))

(def mean
  (aid/build /
             (partial apply +)
             count))

(def get-center
  (comp (partial map mean)
        (juxt (juxt :left
                    :right)
              (juxt :top
                    :bottom))))

(def shrink
  (partial (aid/flip -) (* 2 font-size)))

(def get-left-top
  (juxt :left :top))

(aid/defcurried get-intersection-line-segment
  [f [out in]]
  [(f out)
   (geom/intersect-line ((aid/build rect/rect
                                    :left
                                    :top
                                    :width
                                    :height)
                          in)
                        (line/line2 (f out) (f in)))])

(def get-left-top-line-segment
  (get-intersection-line-segment get-left-top))

(def get-direction
  (partial apply map (comp neg?
                           -)))

(def get-left-top-direction
  (comp get-direction
        (partial map get-left-top)))

(def get-corner-line-segment
  (aid/build (partial map aid/funcall)
             (comp (partial apply map juxt)
                   (partial map (aid/flip aid/funcall) [[:right :left]
                                                        [:bottom :top]])
                   (partial map #(if %
                                   identity
                                   reverse))
                   get-left-top-direction)
             identity))

(def corners?
  (aid/build =
             get-left-top-direction
             (comp get-direction
                   get-corner-line-segment)))

(def get-line-segment
  (aid/if-then-else
    oblique?
    (aid/if-then-else corners?
                      get-corner-line-segment
                      (get-intersection-line-segment get-center))
    (aid/if-then-else (comp horizontal?)
                      (comp (partial s/transform*
                                     [s/ALL s/LAST]
                                     (partial + font-size))
                            get-left-top-line-segment)
                      get-left-top-line-segment)))

(def correspondence
  ((aid/lift-a (fn [m coll]
                 (->> coll
                      (map (aid/if-then-else (partial every? m)
                                             (aid/build hash-map
                                                        identity
                                                        (comp get-line-segment
                                                              (partial map m)))
                                             (constantly {})))
                      (apply merge {}))))
    (->> valid-bounds
         (m/<$> (aid/build zipmap
                           (partial map :id)
                           (partial map
                                    (comp (partial s/transform*
                                                   (s/multi-path :top
                                                                 :bottom)
                                                   (partial + marker-size))
                                          (partial s/transform*
                                                   (s/multi-path :bottom
                                                                 :height)
                                                   shrink)))))
         (frp/stepper {}))
    (->> edge-event
         (m/<$> graph/edges)
         (frp/stepper []))))

(def sink-line-segment
  (m/<$> (partial s/transform* s/MAP-VALS line/line2) correspondence))

(def error
  (m/<$> get-error insert-text))

(def command-text
  (->> command-exit
       (aid/<$ "")
       (m/<> command-typing)
       (frp/stepper "")))

(def mode-event
  (m/<> (aid/<$ :normal normal)
        (aid/<$ :insert (m/<> insert-normal insert-insert))
        (aid/<$ :command command)))

(def mode-behavior
  (frp/stepper :normal mode-event))

(def edge-mode
  (->> source-in
       (m/<> mode-event history)
       (aid/<$ false)
       (m/<> (aid/<$ true edge-node-event))
       (frp/stepper false)))

(def sink-in
  (->> edge-mode
       (frp/snapshot (core/dedupe edge-node-event))
       (core/filter last)
       (m/<$> first)))

(def additional-edge
  (->> edge-node-event
       (frp/stepper node-placeholder)
       (frp/snapshot sink-in)
       (m/<$> reverse)))

(def sink-transform-edge-action
  (m/<$> #(partial s/transform* :edge (partial (aid/flip graph/add-edges) %))
         additional-edge))

(def editor-command
  (->> editor-keyup
       (core/filter (partial = ":"))
       (aid/<$ false)
       (m/<> (m/<$> (comp (partial = ":")
                          last)
                    editor-keydown))
       (frp/stepper false)))

(def file-path-placeholder
  "")

(def buffer-entry
  (->> current-file-path
       (frp/stepper file-path-placeholder)
       (frp/snapshot (m/<$> (partial zipmap [:history :x :y])
                            (frp/snapshot history
                                          cursor-x-behavior
                                          cursor-y-behavior)))
       (m/<$> reverse)))

(aid/defcurried move*
  [to from f m]
  (->> m
       (aid/transfer* to
                      (comp f
                            (partial s/select-one* from)))
       (s/setval from s/NONE)))

(def modification
  (->> buffer-entry
       (m/<$> (partial s/transform*
                       s/LAST
                       (move* :content
                              :history
                              (comp (partial s/transform* :node :canonical)
                                    ffirst))))
       (core/remove (fn [[path* m]]
                      (or (empty? path*)
                          (and (fs/fexists? path*)
                               (-> path*
                                   slurp
                                   read-file
                                   (= m))))))))

(def initial-buffer
  {:history initial-history
   :x       initial-cursor
   :y       initial-cursor})

(def sink-buffer
  (->> buffer-entry
       (m/<$> (partial apply hash-map))
       core/merge
       (frp/stepper {})
       (frp/snapshot current-file-path)
       (m/<$> (fn [[k m]]
                (aid/casep k
                  fs/fexists? (->> k
                                   slurp
                                   read-file
                                   (move* :history
                                          :content
                                          (comp get-history
                                                (partial s/transform*
                                                         :node
                                                         augment)))
                                   (get m k))
                  initial-buffer)))))

(def initial-scroll
  0)

(def initial-maximum
  0)

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

(def editing-bound
  (->> (frp/snapshot valid-bounds
                     cursor-x-behavior
                     cursor-y-behavior)
       (core/partition 2 1)
       (core/filter (comp (partial apply =)
                          (partial map rest)))
       (m/<$> last)
       (m/<> (m/<$> rest
                    (frp/snapshot insert-insert
                                  (frp/stepper [] valid-bounds)
                                  cursor-x-behavior
                                  cursor-y-behavior)))
       (m/<$> (fn [[m x y]]
                (->> m
                     (filter (comp (partial = x)
                                   :x))
                     (filter (comp (partial = y)
                                   :y))
                     (aid/if-then-else empty?
                                       (constantly {})
                                       first))))))

(def get-editing-bound
  #(m/<$> (comp (partial (aid/flip quot) cursor-size)
                (partial max initial-maximum)
                %)
          editing-bound))

(def editing-x-bound
  (get-editing-bound :right))

(def editing-y-bound
  (get-editing-bound :bottom))

(def opening
  (->> (aid/<$ true source-buffer)
       (m/<> (aid/<$ false (m/<> action valid-expression)))
       (frp/stepper true)))

(defn get-scroll
  [client bound offset* cursor]
  (->> client
       (frp/snapshot (->> cursor
                          (frp/stepper initial-cursor)
                          (frp/snapshot bound opening)
                          (m/<$> (fn [[bound* opening* cursor*]]
                                   (if opening*
                                     cursor*
                                     (max bound* cursor*))))
                          (m/<> cursor)))
       (core/reduce (fn [reduction [x view-size]]
                      (-> reduction
                          (max (-> x
                                   inc
                                   (* cursor-size)
                                   (- (- view-size offset*))))
                          (min (* x cursor-size))))
                    initial-scroll)
       (frp/stepper initial-scroll)))

(def sink-scroll-x
  (get-scroll client-width editing-x-bound marker-size cursor-x-event))

(def sink-scroll-y
  (get-scroll client-height editing-y-bound 0 cursor-y-event))

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
  (get-maximum client-width sink-scroll-x editing-x-bound cursor-x-behavior))

(def maximum-y
  (get-maximum client-height sink-scroll-y editing-y-bound cursor-y-behavior))

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

(def get-status-text
  #(.keyBinding.getStatusText (:editor %) (:editor %)))

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
                 #(editor-keydown [(get-status-text @state)
                                   (.-key %)])))
             (-> @state
                 :editor
                 .textInput.getElement
                 (.addEventListener
                   "keyup"
                   (fn [event*]
                     (-> @state
                         get-status-text
                         editor-keyup)
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
           (fn [mode text]
             (swap! state (partial s/setval* :mode mode))
             [:> ace-editor
              {:commands         [{:name    "`"
                                   :bindKey "`"
                                   :exec    aid/nop}
                                  {:bindKey "l"
                                   :exec    #(.insert (:editor @state)
                                                      (aid/casep @state
                                                        :backtick "\\lambda"
                                                        "l"))}]
               :focus            (= :insert mode)
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
               :value            text}])})))

(defn math
  [s]
  [:div
   {:dangerouslySetInnerHTML
    {:__html (js/katex.renderToString s
                                      #js {:displayMode true})}}])

(def background-color
  "black")

(def selection-color
  "grey")

(def color
  "white")

(def outline-width
  1)

(defn get-node-color
  [mode edge-node id*]
  (if (and mode
           (= edge-node id*))
    selection-color
    background-color))

(defn math-node
  [& _]
  (let [state (r/atom {:height maximum-pixel})]
    (fn [mode edge-node [id* {:keys [value x y]}]]
      [:g
       [:rect (merge (s/transform :height shrink @state)
                     {:fill  (get-node-color mode edge-node id*)
                      :style {:outline-color (get-node-color mode
                                                             edge-node
                                                             id*)
                              :outline-style "solid"
                              :outline-width outline-width}
                      :x     (get-x-cursor-pixel x)
                      :y     (* y cursor-size)})]
       ^{:key id*}
       [:> measure
        {:bounds    true
         :on-resize #(-> %
                         .-bounds
                         (js->clj :keywordize-keys true)
                         ((juxt (comp bounds
                                      (partial s/setval* :id id*))
                                (partial reset! state))))}
        #(r/as-element [:foreignObject {:x (get-x-cursor-pixel x)
                                        :y (* y cursor-size)}
                        [:div {:ref   (.-measureRef %)
                               :style {:display       "inline-block"
                                       :margin-bottom (- marker-size)
                                       :margin-top    (- marker-size)}}
                         [math (align value)]]])]])))

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
   :color            color
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
      [mode command-text*]
      [:form {:style     {:display (case mode
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
        (convert-keys #{"clientWidth" "clientHeight"})
        r/dom-node))

(defc nodes
      [mode edge-node canonical*]
      (->> canonical*
           (mapv (partial vector math-node mode edge-node))
           (s/setval s/BEFORE-ELEM :g)))

(def edge-component
  (comp (partial vector :line)
        ;TODO add :on-click
        (partial s/setval* :style {:marker-end   "url(#arrow)"
                                   :stroke-width 1
                                   :stroke       color})
        (partial zipmap [:x1 :y1 :x2 :y2])
        flatten
        last))

(defc edges-component
      [correspondence*]
      (->> correspondence*
           (mapv edge-component)
           (s/setval s/BEFORE-ELEM :g)))

(defc blockwise-visual-component
      [mode [x0 y0] x1 y1]
      [:rect (if mode
               {:fill   selection-color
                :height (get-size y1 y0)
                :width  (get-size x1 x0)
                :x      (get-x-cursor-pixel (min x0 x1))
                :y      (* (min y0 y1) cursor-size)}
               {})])

(def ref-x
  2)

(def view-box
  (->> ref-x
       (repeat 2)
       (concat (repeat 2 0))
       (str/join " ")))

(def ref-y
  1)

(def path-d
  (str/join " " ["M" 0 0 "L" ref-x ref-y "L" 0 ref-x "z"]))

(defc graph-component
      [& _]
      (let [state (atom {})]
        (r/create-class
          {:component-did-mount  (fn [this]
                                   (dom* this)
                                   (js/window.addEventListener "resize"
                                                               (fn [_]
                                                                 (dom* this))))
           :component-did-update #(-> %
                                      r/dom-node
                                      (.scrollTo (:x @state)
                                                 (:y @state)))
           :reagent-render       (fn [scroll-x*
                                      scroll-y*
                                      maximum-x
                                      maximum-y
                                      edge-mode*
                                      edge-node
                                      canonical*
                                      correspondence*
                                      blockwise-visual-mode*
                                      blockwise-visual-node*
                                      cursor-x
                                      cursor-y]
                                   (swap! state (partial (aid/flip merge)
                                                         {:x scroll-x*
                                                          :y scroll-y*}))
                                   [:div {:style {:height   "100%"
                                                  :overflow "scroll"
                                                  :width    "100%"}}
                                    [:svg {:style {:height maximum-y
                                                   :width  maximum-x}}
                                     [:marker {:id            "arrow"
                                               :marker-width  marker-size
                                               :marker-height marker-size
                                               :orient        "auto"
                                               :ref-x         ref-x
                                               :ref-y         ref-y
                                               :view-box      view-box}
                                      [:path {:d    path-d
                                              :fill color}]]
                                     [blockwise-visual-component
                                      blockwise-visual-mode*
                                      blockwise-visual-node*
                                      cursor-x
                                      cursor-y]
                                     [edges-component correspondence*]
                                     [nodes edge-mode* edge-node canonical*]
                                     [:rect
                                      {:height  cursor-size
                                       :opacity 0
                                       :style   {:outline-color  "red"
                                                 :outline-offset (-
                                                                   outline-width)
                                                 :outline-style  "solid"
                                                 :outline-width  outline-width}
                                       :width   cursor-size
                                       :x       (get-x-cursor-pixel cursor-x)
                                       :y       (* cursor-y cursor-size)}]]])})))

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
                               :color            color
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
    edge-mode
    edge-node-behavior
    canonical
    correspondence
    blockwise-visual-mode
    blockwise-visual-node
    cursor-x-behavior
    cursor-y-behavior))

(def command-view
  ((aid/lift-a command-component) mode-behavior command-text))

(def editor-view
  ((aid/lift-a editor) mode-behavior insert-text))

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

(loop-event {source-buffer                sink-buffer
             source-content               sink-content
             source-directory             sink-directory
             source-edge-register         sink-edge-register
             source-in                    sink-in
             source-line-segment          sink-line-segment
             source-node-register         sink-node-register
             source-scroll-x              sink-scroll-x
             source-scroll-y              sink-scroll-y
             source-transform-edge-action sink-transform-edge-action})

(frp/run #(oset! js/document "title" %) current-file-path)

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
   "ctrl+v" blockwise-visual-toggle
   "escape" escape
   "h"      left
   "i"      insert-insert
   "j"      down
   "k"      up
   "l"      right
   "p"      paste
   "space"  insert-normal
   "u"      undo
   "x"      delete})

(bind-keymap keymap)

(.config.loadModule ace
                    "ace/keyboard/vim"
                    #(run! (partial apply (.-CodeMirror.Vim.map %))
                           (combo/cartesian-product ["jk" "kj"]
                                                    ["<Esc>"]
                                                    ["insert" "command"])))

(.ipcRenderer.on helpers/electron helpers/channel (comp potential-file-path
                                                        last
                                                        vector))

(frp/run (partial apply spit) modification)

(frp/activate)

(run! submission config-commands)
