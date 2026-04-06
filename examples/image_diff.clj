(ns image-diff
  "Compare two same-sized images and write a red diff overlay.

  Example:

  `clojure -M:dev examples/image_diff.clj -- dev/donkey.png dev/donkey-2.png examples/donkey_diff_overlay.png`"
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def difference-threshold
  30.0)

(def highlight-opacity
  0.65)

(def highlight-color
  [255.0 0.0 0.0])

(defn- usage!
  []
  (binding [*out* *err*]
    (println "usage: clojure -M:dev examples/image_diff.clj <before-path> <after-path> <output-path>")
    (println "example: clojure -M:dev examples/image_diff.clj -- dev/donkey.png dev/donkey-2.png examples/donkey_diff_overlay.png"))
  (System/exit 1))

(defn- ensure-matching-shape!
  [before after before-path after-path]
  (when-not (= (v/shape before) (v/shape after))
    (throw (ex-info "image diff example expects inputs with matching dimensions after flattening"
                    {:before-path  before-path
                     :before-shape (v/shape before)
                     :after-path   after-path
                     :after-shape  (v/shape after)}))))

(defn -main
  [& args]
  (let [[before-path after-path output-path]
        (if (= "--" (first args))
          (rest args)
          args)]
    (when-not (and before-path after-path output-path (= 3 (count (remove nil? [before-path after-path output-path]))))
      (usage!))
    (when-let [parent (fs/parent output-path)]
      (fs/create-dirs parent))
    (with-open [before           (v/from-file before-path)
                after            (v/from-file after-path)
                before-flattened (ops/flatten before {:background [255]})
                after-flattened  (ops/flatten after {:background [255]})]
      (ensure-matching-shape! before-flattened after-flattened before-path after-path)
      (with-open [before-float      (ops/cast before-flattened :float)
                  after-float       (ops/cast after-flattened :float)
                  absolute-diff     (-> (ops/subtract before-float after-float)
                                        (ops/abs))
                  diff-intensity    (ops/bandmean absolute-diff)
                  diff-mask         (ops/relational-const diff-intensity :more [difference-threshold])
                  overlay-base      (ops/black (v/width after-flattened)
                                               (v/height after-flattened)
                                               {:bands 3})
                  after-rgba        (ops/addalpha after-flattened)
                  highlight-rgb     (ops/linear overlay-base
                                                [0.0 0.0 0.0]
                                                highlight-color
                                                {:uchar true})
                  highlight-alpha   (ops/linear diff-mask [highlight-opacity] [0.0] {:uchar true})
                  highlight-overlay (-> (ops/bandjoin [highlight-rgb highlight-alpha])
                                        (ops/copy {:interpretation :srgb}))
                  result-rgba       (ops/composite2 after-rgba highlight-overlay :over)
                  result            (ops/flatten result-rgba {:background [255]})]
        (v/write-to-file result output-path)
        (println "mean absolute difference:" (double (:out (ops/avg diff-intensity))))
        (println "highlight coverage:" (double (/ (:out (ops/avg diff-mask)) 255.0)))
        (println (str "diff overlay: " output-path))))))

(apply -main *command-line-args*)
