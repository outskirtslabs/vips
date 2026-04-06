(ns funcgeo2-fish
  "Functional Geometry fish example rendered with `ol.vips`.

  This example renders the escher fish from Peter Henderson's paper
  \"Functional Geometry\" (the revised 2002 edition).

  Run it with:

  ```clojure
  clojure -M:dev examples/funcgeo2_fish.clj"
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def output-01-fish-path
  (fs/path "examples" "funcgeo2-fish-01-fish.png"))

(def output-02-fish-over-path
  (fs/path "examples" "funcgeo2-fish-02-fish-over.png"))

(def output-03-t-tile-path
  (fs/path "examples" "funcgeo2-fish-03-t-tile.png"))

(def output-04-u-tile-path
  (fs/path "examples" "funcgeo2-fish-04-u-tile.png"))

(def output-05-side-path
  (fs/path "examples" "funcgeo2-fish-05-side.png"))

(def output-06-corner-path
  (fs/path "examples" "funcgeo2-fish-06-corner.png"))

(def output-07-square-limit-path
  (fs/path "examples" "funcgeo2-fish-07-square-limit.png"))

(def line-ink
  [0 0 0])

(def output-scale
  2)

(def square-limit-output-scale
  4)

(def canvas-padding
  24)

(def max-pixel-stroke-width
  4)

(defn make-vect
  [x y]
  {:x x :y y})

(defn add-vect
  [v1 v2]
  (make-vect (+ (:x v1) (:x v2))
             (+ (:y v1) (:y v2))))

(defn neg-vect
  [v]
  (make-vect (- (:x v))
             (- (:y v))))

(defn sub-vect
  [v1 v2]
  (add-vect v1 (neg-vect v2)))

(defn scale-vect
  [factor v]
  (make-vect (* factor (:x v))
             (* factor (:y v))))

(defn length-vect
  [v]
  (Math/sqrt (+ (* (:x v) (:x v))
                (* (:y v) (:y v)))))

(defn make-box
  [a b c]
  {:a a :b b :c c})

(defn centered-box
  [width height box-size]
  (let [margin-x (/ (- width box-size) 2.0)
        margin-y (/ (- height box-size) 2.0)]
    (make-box (make-vect margin-x margin-y)
              (make-vect box-size 0.0)
              (make-vect 0.0 box-size))))

(defn turn-box
  [{:keys [a b c]}]
  (make-box (add-vect a b)
            c
            (neg-vect b)))

(defn flip-box
  [{:keys [a b c]}]
  (make-box (add-vect a b)
            (neg-vect b)
            c))

(defn toss-box
  [{:keys [a b c]}]
  (make-box (add-vect a (scale-vect 0.5 (add-vect b c)))
            (scale-vect 0.5 (add-vect b c))
            (scale-vect 0.5 (sub-vect c b))))

(defn split-vertically
  [fraction {:keys [a b c]}]
  (let [top-height    (scale-vect fraction c)
        bottom-height (scale-vect (- 1.0 fraction) c)]
    [(make-box (add-vect a bottom-height) b top-height)
     (make-box a b bottom-height)]))

(defn split-horizontally
  [fraction {:keys [a b c]}]
  (let [left-width  (scale-vect fraction b)
        right-width (scale-vect (- 1.0 fraction) b)]
    [(make-box a left-width c)
     (make-box (add-vect a left-width) right-width c)]))

(defn make-curve
  [p1 p2 p3 p4]
  {:kind :curve
   :p1   p1
   :p2   p2
   :p3   p3
   :p4   p4})

(def fish-curves
  [(make-curve (make-vect 0.116 0.702) (make-vect 0.260 0.295) (make-vect 0.330 0.258) (make-vect 0.815 0.078))
   (make-curve (make-vect 0.564 0.032) (make-vect 0.730 0.056) (make-vect 0.834 0.042) (make-vect 1.000 0.000))
   (make-curve (make-vect 0.250 0.250) (make-vect 0.372 0.194) (make-vect 0.452 0.132) (make-vect 0.564 0.032))
   (make-curve (make-vect 0.000 0.000) (make-vect 0.110 0.110) (make-vect 0.175 0.175) (make-vect 0.250 0.250))
   (make-curve (make-vect -0.250 0.250) (make-vect -0.150 0.150) (make-vect -0.090 0.090) (make-vect 0.000 0.000))
   (make-curve (make-vect -0.250 0.250) (make-vect -0.194 0.372) (make-vect -0.132 0.452) (make-vect -0.032 0.564))
   (make-curve (make-vect -0.032 0.564) (make-vect 0.055 0.355) (make-vect 0.080 0.330) (make-vect 0.250 0.250))
   (make-curve (make-vect -0.032 0.564) (make-vect -0.056 0.730) (make-vect -0.042 0.834) (make-vect 0.000 1.000))
   (make-curve (make-vect 0.000 1.000) (make-vect 0.104 0.938) (make-vect 0.163 0.893) (make-vect 0.234 0.798))
   (make-curve (make-vect 0.234 0.798) (make-vect 0.368 0.650) (make-vect 0.232 0.540) (make-vect 0.377 0.377))
   (make-curve (make-vect 0.377 0.377) (make-vect 0.400 0.350) (make-vect 0.450 0.300) (make-vect 0.500 0.250))
   (make-curve (make-vect 0.500 0.250) (make-vect 0.589 0.217) (make-vect 0.660 0.208) (make-vect 0.766 0.202))
   (make-curve (make-vect 0.766 0.202) (make-vect 0.837 0.107) (make-vect 0.896 0.062) (make-vect 1.000 0.000))
   (make-curve (make-vect 0.234 0.798) (make-vect 0.340 0.792) (make-vect 0.411 0.783) (make-vect 0.500 0.750))
   (make-curve (make-vect 0.500 0.750) (make-vect 0.500 0.625) (make-vect 0.500 0.575) (make-vect 0.500 0.500))
   (make-curve (make-vect 0.500 0.500) (make-vect 0.460 0.460) (make-vect 0.410 0.410) (make-vect 0.377 0.377))
   (make-curve (make-vect 0.315 0.710) (make-vect 0.378 0.732) (make-vect 0.426 0.726) (make-vect 0.487 0.692))
   (make-curve (make-vect 0.340 0.605) (make-vect 0.400 0.642) (make-vect 0.435 0.647) (make-vect 0.489 0.626))
   (make-curve (make-vect 0.348 0.502) (make-vect 0.400 0.564) (make-vect 0.422 0.568) (make-vect 0.489 0.563))
   (make-curve (make-vect 0.451 0.418) (make-vect 0.465 0.400) (make-vect 0.480 0.385) (make-vect 0.490 0.381))
   (make-curve (make-vect 0.421 0.388) (make-vect 0.440 0.350) (make-vect 0.455 0.335) (make-vect 0.492 0.325))
   (make-curve (make-vect -0.170 0.237) (make-vect -0.125 0.355) (make-vect -0.065 0.405) (make-vect 0.002 0.436))
   (make-curve (make-vect -0.121 0.188) (make-vect -0.060 0.300) (make-vect -0.030 0.330) (make-vect 0.040 0.375))
   (make-curve (make-vect -0.058 0.125) (make-vect -0.010 0.240) (make-vect 0.030 0.280) (make-vect 0.100 0.321))
   (make-curve (make-vect -0.022 0.063) (make-vect 0.060 0.200) (make-vect 0.100 0.240) (make-vect 0.160 0.282))
   (make-curve (make-vect 0.053 0.658) (make-vect 0.075 0.677) (make-vect 0.085 0.687) (make-vect 0.098 0.700))
   (make-curve (make-vect 0.053 0.658) (make-vect 0.042 0.710) (make-vect 0.042 0.760) (make-vect 0.053 0.819))
   (make-curve (make-vect 0.053 0.819) (make-vect 0.085 0.812) (make-vect 0.092 0.752) (make-vect 0.098 0.700))
   (make-curve (make-vect 0.130 0.718) (make-vect 0.150 0.730) (make-vect 0.175 0.745) (make-vect 0.187 0.752))
   (make-curve (make-vect 0.130 0.718) (make-vect 0.110 0.795) (make-vect 0.110 0.810) (make-vect 0.112 0.845))
   (make-curve (make-vect 0.112 0.845) (make-vect 0.150 0.805) (make-vect 0.172 0.780) (make-vect 0.187 0.752))])

(defn mapper
  [{:keys [a b c]}]
  (fn [{:keys [x y]}]
    (add-vect a
              (add-vect (scale-vect x b)
                        (scale-vect y c)))))

(defn box-stroke-width
  [{:keys [b c]}]
  (min (double max-pixel-stroke-width)
       (max 1.0
            (/ (max (length-vect b) (length-vect c)) 120.0))))

(defn create-picture
  [shapes]
  (fn [box]
    (let [m            (mapper box)
          stroke-width (box-stroke-width box)]
      (mapv (fn [{:keys [p1 p2 p3 p4]}]
              {:kind         :curve
               :stroke-width stroke-width
               :p1           (m p1)
               :p2           (m p2)
               :p3           (m p3)
               :p4           (m p4)})
            shapes))))

(def blank
  (constantly []))

(defn turn
  [picture]
  (comp picture turn-box))

(defn flip
  [picture]
  (comp picture flip-box))

(defn toss
  [picture]
  (comp picture toss-box))

(defn above-ratio
  [m n p1 p2]
  (fn [box]
    (let [fraction             (/ (double m) (+ m n))
          [top-box bottom-box] (split-vertically fraction box)]
      (into [] cat [(p1 top-box) (p2 bottom-box)]))))

(defn above
  [p1 p2]
  (above-ratio 1 1 p1 p2))

(defn beside-ratio
  [m n p1 p2]
  (fn [box]
    (let [fraction             (/ (double m) (+ m n))
          [left-box right-box] (split-horizontally fraction box)]
      (into [] cat [(p1 left-box) (p2 right-box)]))))

(defn beside
  [p1 p2]
  (beside-ratio 1 1 p1 p2))

(defn quartet
  [nw ne sw se]
  (above (beside nw ne)
         (beside sw se)))

(defn nonet
  [nw nm ne mw mm me sw sm se]
  (let [row    (fn [w m e]
                 (beside-ratio 1 2 w (beside m e)))
        column (fn [n m s]
                 (above-ratio 1 2 n (above m s)))]
    (column (row nw nm ne)
            (row mw mm me)
            (row sw sm se))))

(defn over
  [p1 p2]
  (fn [box]
    (into [] cat [(p1 box) (p2 box)])))

(defn ttile
  [fish]
  (let [fish-n (-> fish toss flip)
        fish-e (-> fish-n turn turn turn)]
    (over fish (over fish-n fish-e))))

(defn utile
  [fish]
  (let [fish-n (-> fish toss flip)
        fish-w (turn fish-n)
        fish-s (turn fish-w)
        fish-e (turn fish-s)]
    (over fish-n
          (over fish-w
                (over fish-s fish-e)))))

(defn side
  [n fish]
  (if (zero? n)
    blank
    (let [s (side (dec n) fish)
          t (ttile fish)]
      (quartet s s (turn t) t))))

(defn corner
  [n fish]
  (if (zero? n)
    blank
    (let [c (corner (dec n) fish)
          s (side (dec n) fish)]
      (quartet c s (turn s) (utile fish)))))

(defn square-limit
  [n fish]
  (let [c  (corner n fish)
        s  (side n fish)
        nw c
        nm s
        ne (-> c turn turn turn)
        mw (turn s)
        mm (utile fish)
        me (-> s turn turn turn)
        sw (turn c)
        sm (-> s turn turn)
        se (-> c turn turn)]
    (nonet nw nm ne mw mm me sw sm se)))

(defn blank-canvas
  [width height]
  (with-open [base  (ops/black width height {:bands 3})
              white (ops/invert base)]
    (v/copy-memory white)))

(defn padded-canvas
  [width height]
  [(+ width (* 2 canvas-padding))
   (+ height (* 2 canvas-padding))])

(defn scaled-size
  [size]
  (* output-scale size))

(defn scaled-size*
  [scale size]
  (* scale size))

(defn mirror-vect
  [height {:keys [x y]}]
  (make-vect x (- height y)))

(defn pixel-point
  [height point]
  (let [{:keys [x y]} (mirror-vect height point)]
    [(long (Math/round (double x)))
     (long (Math/round (double y)))]))

(defn segment-normal
  [start end]
  (let [delta  (sub-vect end start)
        length (length-vect delta)]
    (if (zero? length)
      (make-vect 0.0 0.0)
      (make-vect (/ (- (:y delta)) length)
                 (/ (:x delta) length)))))

(defn cubic-point
  [p0 p1 p2 p3 t]
  (let [u   (- 1.0 t)
        uu  (* u u)
        uuu (* uu u)
        tt  (* t t)
        ttt (* tt t)]
    (-> (scale-vect uuu p0)
        (add-vect (scale-vect (* 3.0 uu t) p1))
        (add-vect (scale-vect (* 3.0 u tt) p2))
        (add-vect (scale-vect ttt p3)))))

(defn curve-approx-length
  [{:keys [p1 p2 p3 p4]}]
  (+ (length-vect (sub-vect p2 p1))
     (length-vect (sub-vect p3 p2))
     (length-vect (sub-vect p4 p3))))

(defn sample-curve
  [{:keys [p1 p2 p3 p4 stroke-width] :as curve}]
  (let [steps (-> (/ (curve-approx-length curve)
                     (max 4.0 (* 3.0 (double stroke-width))))
                  Math/ceil
                  long
                  (max 4)
                  (min 12))]
    (mapv (fn [idx]
            (cubic-point p1 p2 p3 p4 (/ idx (double steps))))
          (range (inc steps)))))

(defn draw-stroked-segment!
  [canvas height stroke-width start end]
  (let [normal      (segment-normal start end)
        pixel-width (max 1 (long (Math/round (double stroke-width))))
        center      (/ (dec pixel-width) 2.0)]
    (doseq [pass (range pixel-width)]
      (let [offset  (scale-vect (- pass center) normal)
            [x1 y1] (pixel-point height (add-vect start offset))
            [x2 y2] (pixel-point height (add-vect end offset))]
        (ops/draw-line canvas line-ink x1 y1 x2 y2))))
  canvas)

(defn render-curve!
  [canvas canvas-height {:keys [stroke-width] :as curve}]
  (doseq [[start end] (partition 2 1 (sample-curve curve))]
    (draw-stroked-segment! canvas canvas-height stroke-width start end))
  canvas)

(defn render-picture
  [picture canvas-width canvas-height box]
  (let [canvas (blank-canvas canvas-width canvas-height)]
    (try
      (doseq [shape (picture box)]
        (render-curve! canvas canvas-height shape))
      canvas
      (catch Throwable t
        (.close ^java.lang.AutoCloseable canvas)
        (throw t)))))

(def fish-picture
  (create-picture fish-curves))

(def output-specs
  (let [fish-canvas         (padded-canvas (scaled-size 302) (scaled-size 200))
        t-tile-canvas       (padded-canvas (scaled-size 352) (scaled-size 300))
        u-tile-canvas       (padded-canvas (scaled-size 402) (scaled-size 402))
        side-corner-canvas  (padded-canvas (scaled-size 226) (scaled-size 232))
        square-limit-canvas (padded-canvas (scaled-size* square-limit-output-scale 300)
                                           (scaled-size* square-limit-output-scale 300))]
    [{:label   "01-fish"
      :path    output-01-fish-path
      :picture fish-picture
      :canvas  fish-canvas
      :box     (apply centered-box (concat fish-canvas [(scaled-size 150)]))}
     {:label   "02-fish-over"
      :path    output-02-fish-over-path
      :picture (over fish-picture (-> fish-picture turn turn))
      :canvas  fish-canvas
      :box     (apply centered-box (concat fish-canvas [(scaled-size 150)]))}
     {:label   "03-t-tile"
      :path    output-03-t-tile-path
      :picture (ttile fish-picture)
      :canvas  t-tile-canvas
      :box     (apply centered-box (concat t-tile-canvas [(scaled-size 150)]))}
     {:label   "04-u-tile"
      :path    output-04-u-tile-path
      :picture (utile fish-picture)
      :canvas  u-tile-canvas
      :box     (apply centered-box (concat u-tile-canvas [(scaled-size 150)]))}
     {:label   "05-side"
      :path    output-05-side-path
      :picture (side 2 fish-picture)
      :canvas  side-corner-canvas
      :box     (apply centered-box (concat side-corner-canvas [(scaled-size 200)]))}
     {:label   "06-corner"
      :path    output-06-corner-path
      :picture (corner 2 fish-picture)
      :canvas  side-corner-canvas
      :box     (apply centered-box (concat side-corner-canvas [(scaled-size 200)]))}
     {:label   "07-square-limit"
      :path    output-07-square-limit-path
      :picture (square-limit 3 fish-picture)
      :canvas  square-limit-canvas
      :box     (apply centered-box (concat square-limit-canvas
                                           [(scaled-size* square-limit-output-scale 250)]))}]))

(defn write-output!
  [{:keys [label path picture canvas box]}]
  (let [[width height] canvas]
    (with-open [image (render-picture picture width height box)]
      (v/write-to-file image path))
    (println (str label ": " path))))

(defn -main
  [& _]
  (v/init!)
  (fs/create-dirs "examples")
  (doseq [spec output-specs]
    (write-output! spec)))

(-main)
