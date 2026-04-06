(ns palette-extractor
  "Extract dominant colors from an image and render a swatch-strip preview.

  This example:

  - computes a small RGB cube histogram with `hist_find_ndim`
  - ranks the most frequent color bins in Clojure
  - reads per-channel means from `stats`
  - draws a color swatch strip with `draw-rect`
  - joins the strip below the source image and writes the result

  Run it with:

  ```clojure
  clojure -M:dev examples/palette_extractor.clj
  ```

  Optional positional args are `input-path`, `output-path`, `top-n`, and
  `bins`."
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def default-input-path
  (fs/path "test" "fixtures" "puppies.jpg"))

(def default-output-path
  "examples/puppies_dominant_palette.png")

(def default-top-n 6)
(def default-bins 6)
(def strip-height 80)

(def stats-column->index
  {:min  0
   :max  1
   :sum  2
   :sum2 3
   :avg  4
   :sd   5
   :xmin 6
   :ymin 7
   :xmax 8
   :ymax 9})

(defn- parse-int-arg
  [value fallback]
  (try
    (Long/parseLong (str value))
    (catch Exception _
      fallback)))

(defn- decode-u32-values
  [bytes]
  (let [buffer (doto (java.nio.ByteBuffer/wrap bytes)
                 (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (loop [values []]
      (if (.hasRemaining buffer)
        (recur (conj values (bit-and 0xffffffff (.getInt buffer))))
        values))))

(defn- decode-double-values
  [bytes]
  (let [buffer (doto (java.nio.ByteBuffer/wrap bytes)
                 (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (loop [values []]
      (if (.hasRemaining buffer)
        (recur (conj values (.getDouble buffer)))
        values))))

(defn- raw-values
  [image decoder]
  (let [temp-path (java.nio.file.Files/createTempFile
                   "ol-vips-example-"
                   ".raw"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (ops/rawsave image temp-path)
      (decoder (java.nio.file.Files/readAllBytes temp-path))
      (finally
        (java.nio.file.Files/deleteIfExists temp-path)))))

(defn- stats-rows
  [image]
  (with-open [stats (ops/stats image)]
    (->> (raw-values stats decode-double-values)
         (partition 10)
         (mapv vec))))

(defn- channel-means
  [image]
  (let [rows      (stats-rows image)
        avg-index (stats-column->index :avg)]
    (mapv #(nth % avg-index) (rest rows))))

(defn- bin-midpoint
  [bin-index bins]
  (let [bin-size (/ 256.0 bins)
        midpoint (+ (* bin-index bin-size)
                    (/ bin-size 2.0))]
    (-> midpoint
        Math/floor
        int
        (min 255))))

(defn- dominant-colors
  [image bins top-n]
  (with-open [histogram (ops/hist-find-ndim image {:bins bins})]
    (let [counts (raw-values histogram decode-u32-values)
          width  (v/width histogram)
          bands  (v/bands histogram)]
      (->> counts
           (map-indexed
            (fn [index count]
              (let [pixel-index (quot index bands)
                    x           (mod pixel-index width)
                    y           (quot pixel-index width)
                    z           (mod index bands)]
                {:count count
                 :bins  [x y z]
                 :rgb   (mapv #(bin-midpoint % bins) [x y z])})))
           (filter (comp pos? :count))
           (sort-by (juxt (comp - :count) :bins))
           (take top-n)
           vec))))

(defn- hex-color
  [[r g b]]
  (format "#%02X%02X%02X" r g b))

(defn- swatch-layout
  [total-width count]
  (let [base-width (quot total-width count)
        remainder  (mod total-width count)]
    (loop [index  0
           left   0
           layout []]
      (if (= index count)
        layout
        (let [width (+ base-width (if (< index remainder) 1 0))]
          (recur (inc index)
                 (+ left width)
                 (conj layout {:left  left
                               :width width})))))))

(defn- palette-strip
  [width colors]
  (with-open [base   (ops/black width strip-height {:bands 3})
              canvas (v/copy-memory base)]
    (doseq [[{:keys [left width]} {:keys [rgb]}]
            (map vector (swatch-layout width (count colors)) colors)]
      (ops/draw-rect canvas rgb left 0 width strip-height {:fill true}))
    (v/copy-memory canvas)))

(defn- srgb-source
  [image]
  (if (v/has-alpha? image)
    (-> image
        (ops/flatten {:background [255 255 255]})
        (ops/colourspace :srgb))
    (ops/colourspace image :srgb)))

(defn- print-summary!
  [colors means output-path]
  (println (format "top %d dominant colors:" (count colors)))
  (doseq [[index {:keys [rgb count]}] (map-indexed vector colors)]
    (println (format "  %d. %s count=%d rgb=%s"
                     (inc index)
                     (hex-color rgb)
                     count
                     (pr-str rgb))))
  (println (format "channel means (stats): R=%.1f G=%.1f B=%.1f"
                   (nth means 0)
                   (nth means 1)
                   (nth means 2)))
  (println (str "palette preview: " output-path)))

(defn -main
  [& args]
  (let [[input-arg output-arg top-n-arg bins-arg] (remove #{"--"} args)
        input-path                                (or input-arg default-input-path)
        output-path                               (str (or output-arg default-output-path))
        top-n                                     (int (parse-int-arg top-n-arg default-top-n))
        bins                                      (int (parse-int-arg bins-arg default-bins))]
    (fs/create-dirs (or (fs/parent output-path) "examples"))
    (with-open [input  (v/from-file input-path)
                source (srgb-source input)]
      (let [colors (dominant-colors source bins top-n)
            means  (channel-means source)]
        (with-open [strip   (palette-strip (v/width source) colors)
                    preview (ops/join source strip :vertical)]
          (v/write-to-file preview output-path)
          (print-summary! colors means output-path))))))

(-main)
