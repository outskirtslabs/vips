(ns sicp-picture-lang
  "SICP picture-language example rendered with `ol.vips`.

  Based on section 2.2.4, \"Example: A Picture Language,\" from Structure and
  Interpretation of Computer Programs by Hal Abelson and Gerald Jay Sussman.
  The painter model in that section descends from Peter Henderson's 1982 paper
  \"Functional Geometry.\"

  Running this script writes a numbered progression to `examples/`, from a
  single `george` through `george4`, `right-split`, `corner-split`, and
  `square-limit`, the final escher-like image.

  Run it with:

  ```clojure
  clojure -M:dev examples/sicp_picture_lang.clj
  ```"
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def output-01-george-path
  (fs/path "examples" "sicp-piclang-01-george.png"))

(def output-02-george4-path
  (fs/path "examples" "sicp-piclang-02-george4.png"))

(def output-03-right-split-path
  (fs/path "examples" "sicp-piclang-03-right-split.png"))

(def output-04-corner-split-path
  (fs/path "examples" "sicp-piclang-04-corner-split.png"))

(def output-05-square-limit-path
  (fs/path "examples" "sicp-piclang-05-square-limit.png"))

(def line-ink
  [0 0 0])

(def background-ink
  [255 255 255])

(def stroke-width
  3)

(def default-margin
  24.0)

(defn make-vect
  [x y]
  [x y])

(defn xcor-vect
  [[x _]]
  x)

(defn ycor-vect
  [[_ y]]
  y)

(defn add-vect
  [[x1 y1] [x2 y2]]
  [(+ x1 x2) (+ y1 y2)])

(defn sub-vect
  [[x1 y1] [x2 y2]]
  [(- x1 x2) (- y1 y2)])

(defn scale-vect
  [scalar [x y]]
  [(* scalar x) (* scalar y)])

(defn make-frame
  [origin edge1 edge2]
  {:origin origin
   :edge1  edge1
   :edge2  edge2})

(defn origin-frame
  [frame]
  (:origin frame))

(defn edge1-frame
  [frame]
  (:edge1 frame))

(defn edge2-frame
  [frame]
  (:edge2 frame))

(defn frame-coord-map
  [frame]
  (fn [vect]
    (add-vect
     (origin-frame frame)
     (add-vect (scale-vect (xcor-vect vect) (edge1-frame frame))
               (scale-vect (ycor-vect vect) (edge2-frame frame))))))

(defn make-segment
  [start end]
  [start end])

(defn start-segment
  [[start _]]
  start)

(defn end-segment
  [[_ end]]
  end)

(defn pixel-round
  [value]
  (long (Math/round (double value))))

(defn pixel-vect
  [vect]
  [(pixel-round (xcor-vect vect))
   (pixel-round (ycor-vect vect))])

(defn draw-stroked-line!
  [canvas start end]
  (let [[x1 y1] (pixel-vect start)
        [x2 y2] (pixel-vect end)
        half    (quot stroke-width 2)]
    (doseq [dx (range (- half) (inc half))
            dy (range (- half) (inc half))]
      (ops/draw-line canvas line-ink
                     (+ x1 dx) (+ y1 dy)
                     (+ x2 dx) (+ y2 dy))))
  canvas)

(defn segments->painter
  [segment-list]
  (fn [canvas frame]
    (let [m (frame-coord-map frame)]
      (doseq [segment segment-list]
        (draw-stroked-line! canvas
                            (m (start-segment segment))
                            (m (end-segment segment)))))
    canvas))

(defn transform-painter
  [painter origin corner1 corner2]
  (fn [canvas frame]
    (let [m          (frame-coord-map frame)
          new-origin (m origin)]
      (painter canvas
               (make-frame new-origin
                           (sub-vect (m corner1) new-origin)
                           (sub-vect (m corner2) new-origin))))))

(defn flip-vert
  [painter]
  (transform-painter painter
                     (make-vect 0.0 1.0)
                     (make-vect 1.0 1.0)
                     (make-vect 0.0 0.0)))

(defn flip-horiz
  [painter]
  (transform-painter painter
                     (make-vect 1.0 0.0)
                     (make-vect 0.0 0.0)
                     (make-vect 1.0 1.0)))

(defn rotate90
  [painter]
  (transform-painter painter
                     (make-vect 1.0 0.0)
                     (make-vect 1.0 1.0)
                     (make-vect 0.0 0.0)))

(defn rotate180
  [painter]
  (transform-painter painter
                     (make-vect 1.0 1.0)
                     (make-vect 0.0 1.0)
                     (make-vect 1.0 0.0)))

(defn rotate270
  [painter]
  (transform-painter painter
                     (make-vect 0.0 1.0)
                     (make-vect 0.0 0.0)
                     (make-vect 1.0 1.0)))

(defn beside
  [painter1 painter2]
  (let [split-point (make-vect 0.5 0.0)
        paint-left  (transform-painter painter1
                                       (make-vect 0.0 0.0)
                                       split-point
                                       (make-vect 0.0 1.0))
        paint-right (transform-painter painter2
                                       split-point
                                       (make-vect 1.0 0.0)
                                       (make-vect 0.5 1.0))]
    (fn [canvas frame]
      (paint-left canvas frame)
      (paint-right canvas frame)
      canvas)))

(defn below
  [painter1 painter2]
  (let [split-point  (make-vect 0.0 0.5)
        paint-bottom (transform-painter painter1
                                        (make-vect 0.0 0.0)
                                        (make-vect 1.0 0.0)
                                        split-point)
        paint-top    (transform-painter painter2
                                        split-point
                                        (make-vect 1.0 0.5)
                                        (make-vect 0.0 1.0))]
    (fn [canvas frame]
      (paint-bottom canvas frame)
      (paint-top canvas frame)
      canvas)))

(defn square-of-four
  [tl tr bl br]
  (fn [painter]
    (let [top    (beside (tl painter) (tr painter))
          bottom (beside (bl painter) (br painter))]
      (below bottom top))))

(defn split
  [outer-combine inner-combine]
  (letfn [(splitter [painter n]
            (if (zero? n)
              painter
              (let [smaller (splitter painter (dec n))]
                (outer-combine painter
                               (inner-combine smaller smaller)))))]
    splitter))

(def right-split
  (split beside below))

(def up-split
  (split below beside))

(defn corner-split
  [painter n]
  (if (zero? n)
    painter
    (let [up           (up-split painter (dec n))
          right        (right-split painter (dec n))
          top-left     (beside up up)
          bottom-right (below right right)
          corner       (corner-split painter (dec n))]
      (beside (below painter top-left)
              (below bottom-right corner)))))

(defn flipped-pairs
  [painter]
  ((square-of-four identity flip-vert
                   identity flip-vert)
   painter))

(defn square-limit
  [painter n]
  ((square-of-four flip-horiz identity
                   rotate180 flip-vert)
   (corner-split painter n)))

(def george-image-width
  346.0)

(def george-image-height
  416.0)

(defn george-point
  [x y]
  (make-vect (/ x george-image-width)
             (/ y george-image-height)))

(def george
  (let [left-temple        (george-point 150 george-image-height)
        right-temple       (george-point 194 george-image-height)
        left-ear           (george-point 120 346)
        right-ear          (george-point 220 346)
        left-neck          (george-point 147 277)
        right-neck         (george-point 205 277)
        left-shoulder      (george-point 121 285)
        right-shoulder     (george-point 240 284)
        left-armpit        (george-point 130 234)
        right-armpit       (george-point 225 234)
        left-waist         (george-point 128 148)
        right-waist        (george-point 222 148)
        leg-join           (george-point 178 131)
        left-inner-elbow   (george-point 68 246)
        left-outer-elbow   (george-point 60 197)
        left-radial-wrist  (george-point 0 311)
        left-ulnar-wrist   (george-point 0 267)
        right-inner-elbow  (george-point 295 187)
        right-outer-elbow  (george-point 305 217)
        right-radial-wrist (george-point george-image-width 118)
        right-ulnar-wrist  (george-point george-image-width 160)
        left-outer-ankle   (george-point 89 0)
        left-inner-ankle   (george-point 137 0)
        right-outer-ankle  (george-point 273 0)
        right-inner-ankle  (george-point 222 0)]
    (segments->painter
     [(make-segment left-temple left-ear)
      (make-segment left-ear left-neck)
      (make-segment left-neck left-shoulder)
      (make-segment left-shoulder left-inner-elbow)
      (make-segment left-inner-elbow left-radial-wrist)
      (make-segment left-ulnar-wrist left-outer-elbow)
      (make-segment left-outer-elbow left-armpit)
      (make-segment left-armpit left-waist)
      (make-segment left-waist left-outer-ankle)
      (make-segment left-inner-ankle leg-join)
      (make-segment leg-join right-inner-ankle)
      (make-segment right-outer-ankle right-waist)
      (make-segment right-waist right-armpit)
      (make-segment right-armpit right-inner-elbow)
      (make-segment right-inner-elbow right-radial-wrist)
      (make-segment right-ulnar-wrist right-outer-elbow)
      (make-segment right-outer-elbow right-shoulder)
      (make-segment right-shoulder right-neck)
      (make-segment right-neck right-ear)
      (make-segment right-ear right-temple)])))

(defn blank-canvas
  [width height]
  (with-open [base  (ops/black width height {:bands 3})
              white (ops/invert base)]
    (v/copy-memory white)))

(defn default-frame
  [width height]
  (let [inner-width  (- width (* 2.0 default-margin))
        inner-height (- height (* 2.0 default-margin))]
    (make-frame (make-vect default-margin (- height default-margin))
                (make-vect inner-width 0.0)
                (make-vect 0.0 (- inner-height)))))

(defn render-painter
  ([painter size]
   (render-painter painter size size))
  ([painter width height]
   (let [canvas (blank-canvas width height)]
     (try
       (painter canvas (default-frame width height))
       canvas
       (catch Throwable t
         (.close ^java.lang.AutoCloseable canvas)
         (throw t))))))

(defn write-george!
  []
  (with-open [image (render-painter george 640)]
    (v/write-to-file image output-01-george-path)))

(defn write-george4!
  []
  (with-open [image (render-painter (flipped-pairs george) 640)]
    (v/write-to-file image output-02-george4-path)))

(defn write-right-split!
  []
  (with-open [image (render-painter (right-split george 4) 600)]
    (v/write-to-file image output-03-right-split-path)))

(defn write-corner-split!
  []
  (with-open [image (render-painter (corner-split george 4) 600)]
    (v/write-to-file image output-04-corner-split-path)))

(defn write-square-limit!
  []
  (with-open [image (render-painter (square-limit george 4) 600)]
    (v/write-to-file image output-05-square-limit-path)))

(defn -main
  [& _]
  (v/init!)
  (fs/create-dirs "examples")
  (write-george!)
  (write-george4!)
  (write-right-split!)
  (write-corner-split!)
  (write-square-limit!)
  (println (str "01-george: " output-01-george-path))
  (println (str "02-george4: " output-02-george4-path))
  (println (str "03-right-split: " output-03-right-split-path))
  (println (str "04-corner-split: " output-04-corner-split-path))
  (println (str "05-square-limit: " output-05-square-limit-path)))

(-main)
