(ns ol.vips-examples-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def output-root
  (fs/path "examples"))

(def metadata-output-path
  (fs/path output-root "rabbit_metadata_copy.jpg"))

(def animated-output-path
  (fs/path output-root "cogs_rotated.gif"))

(def image-diff-output-path
  (fs/path output-root "gradient_diff_overlay.png"))

(def dominant-palette-output-path
  (fs/path output-root "puppies_dominant_palette.png"))

(def sicp-01-george-output-path
  (fs/path output-root "sicp-piclang-01-george.png"))

(def sicp-02-george4-output-path
  (fs/path output-root "sicp-piclang-02-george4.png"))

(def sicp-03-right-split-output-path
  (fs/path output-root "sicp-piclang-03-right-split.png"))

(def sicp-04-corner-split-output-path
  (fs/path output-root "sicp-piclang-04-corner-split.png"))

(def sicp-05-square-limit-output-path
  (fs/path output-root "sicp-piclang-05-square-limit.png"))

(def funcgeo2-01-fish-output-path
  (fs/path output-root "funcgeo2-fish-01-fish.png"))

(def funcgeo2-02-fish-over-output-path
  (fs/path output-root "funcgeo2-fish-02-fish-over.png"))

(def funcgeo2-03-t-tile-output-path
  (fs/path output-root "funcgeo2-fish-03-t-tile.png"))

(def funcgeo2-04-u-tile-output-path
  (fs/path output-root "funcgeo2-fish-04-u-tile.png"))

(def funcgeo2-05-side-output-path
  (fs/path output-root "funcgeo2-fish-05-side.png"))

(def funcgeo2-06-corner-output-path
  (fs/path output-root "funcgeo2-fish-06-corner.png"))

(def funcgeo2-07-square-limit-output-path
  (fs/path output-root "funcgeo2-fish-07-square-limit.png"))

(defn- run-example!
  [script-path & args]
  (apply shell/sh
         "clojure"
         "-M:dev"
         script-path
         (concat (when (seq args) ["--"])
                 (map str args))))

(defn- capture-example-outputs
  []
  (into {}
        (for [path [metadata-output-path
                    animated-output-path
                    image-diff-output-path
                    dominant-palette-output-path
                    sicp-01-george-output-path
                    sicp-02-george4-output-path
                    sicp-03-right-split-output-path
                    sicp-04-corner-split-output-path
                    sicp-05-square-limit-output-path
                    funcgeo2-01-fish-output-path
                    funcgeo2-02-fish-over-output-path
                    funcgeo2-03-t-tile-output-path
                    funcgeo2-04-u-tile-output-path
                    funcgeo2-05-side-output-path
                    funcgeo2-06-corner-output-path
                    funcgeo2-07-square-limit-output-path]]
          [path (when (fs/exists? path)
                  (java.nio.file.Files/readAllBytes path))])))

(defn- cleanup-example-outputs!
  [original-outputs]
  (doseq [[path content] original-outputs]
    (if content
      (do
        (fs/create-dirs (fs/parent path))
        (java.nio.file.Files/write path content (make-array java.nio.file.OpenOption 0)))
      (fs/delete-if-exists path))))

(deftest runnable-examples
  (let [original-outputs (capture-example-outputs)]
    (doseq [path [metadata-output-path
                  animated-output-path
                  image-diff-output-path]]
      (fs/delete-if-exists path))
    (try
      (testing "metadata example is runnable and writes the intended persisted headers"
        (let [{:keys [exit out err]} (run-example! "examples/metadata_roundtrip.clj")]
          (is (zero? exit) (str out err))
          (is (fs/exists? metadata-output-path))
          (is (str/includes? out "selected metadata fields:"))
          (with-open [image (v/from-file metadata-output-path)]
            (is (= 10.0 (v/field image "xres")))
            (is (= 10.0 (v/field image "yres")))
            (is (= {:width 2490 :height 3084 :has-alpha? false}
                   (select-keys (v/metadata image) [:width :height :has-alpha?]))))))

      (testing "animated example is runnable and writes an animated gif"
        (let [{:keys [exit out err]} (run-example! "examples/animated_gif.clj")]
          (is (zero? exit) (str out err))
          (is (fs/exists? animated-output-path))
          (is (str/includes? out "animated gif:"))
          (with-open [image (ops/gifload animated-output-path {:n -1})]
            (is (= {:width 70 :height 350 :has-alpha? true}
                   (select-keys (v/metadata image) [:width :height :has-alpha?])))
            (is (= 5 (v/pages image)))
            (is (= 70 (v/page-height image)))
            (is (= 2 (v/loop-count image))))))

      (testing "image diff example is runnable and writes a highlighted overlay"
        (let [before-path (fs/path output-root "gradient_before.png")
              after-path  (fs/path output-root "gradient_after.png")]
          (try
            (with-open [before (v/from-file "test/fixtures/gradient.png")
                        after  (ops/flip before :horizontal)]
              (v/write-to-file before before-path)
              (v/write-to-file after after-path))
            (let [{:keys [exit out err]} (run-example! "examples/image_diff.clj"
                                                       before-path
                                                       after-path
                                                       image-diff-output-path)]
              (is (zero? exit) (str out err))
              (is (fs/exists? image-diff-output-path))
              (is (str/includes? out "mean absolute difference:"))
              (is (str/includes? out "highlight coverage:"))
              (is (str/includes? out "diff overlay:"))
              (with-open [after  (v/from-file after-path)
                          diffed (v/from-file image-diff-output-path)
                          delta  (-> (ops/subtract (ops/cast diffed :float)
                                                   (ops/cast after :float))
                                     (ops/abs)
                                     (ops/bandmean))]
                (is (= (v/shape after) (v/shape diffed)))
                (is (pos? (:out (ops/avg delta))))))
            (finally
              (fs/delete-if-exists before-path)
              (fs/delete-if-exists after-path)))))
      (finally
        (cleanup-example-outputs! original-outputs)))))

(deftest dominant-palette-example
  (let [original-outputs (capture-example-outputs)]
    (fs/delete-if-exists dominant-palette-output-path)
    (try
      (let [{:keys [exit out err]} (run-example! "examples/palette_extractor.clj")]
        (is (zero? exit) (str out err))
        (is (fs/exists? dominant-palette-output-path))
        (is (str/includes? out "top 6 dominant colors:"))
        (is (str/includes? out "channel means (stats):"))
        (is (str/includes? out "palette preview:"))
        (with-open [image (v/from-file dominant-palette-output-path)]
          (is (= {:width 518 :height 469 :has-alpha? false}
                 (select-keys (v/metadata image) [:width :height :has-alpha?])))))
      (finally
        (cleanup-example-outputs! original-outputs)))))

(deftest sicp-picture-language-example
  (let [original-outputs (capture-example-outputs)]
    (doseq [path [sicp-01-george-output-path
                  sicp-02-george4-output-path
                  sicp-03-right-split-output-path
                  sicp-04-corner-split-output-path
                  sicp-05-square-limit-output-path]]
      (fs/delete-if-exists path))
    (try
      (let [{:keys [exit out err]} (run-example! "examples/sicp_picture_lang.clj")]
        (is (zero? exit) (str out err))
        (is (str/includes? out "01-george:"))
        (is (str/includes? out "02-george4:"))
        (is (str/includes? out "03-right-split:"))
        (is (str/includes? out "04-corner-split:"))
        (is (str/includes? out "05-square-limit:"))
        (with-open [george       (v/from-file sicp-01-george-output-path)
                    george4      (v/from-file sicp-02-george4-output-path)
                    right-split  (v/from-file sicp-03-right-split-output-path)
                    corner-split (v/from-file sicp-04-corner-split-output-path)
                    square-limit (v/from-file sicp-05-square-limit-output-path)]
          (is (= {:width 640 :height 640 :has-alpha? false}
                 (select-keys (v/metadata george) [:width :height :has-alpha?])))
          (is (= {:width 640 :height 640 :has-alpha? false}
                 (select-keys (v/metadata george4) [:width :height :has-alpha?])))
          (is (= {:width 600 :height 600 :has-alpha? false}
                 (select-keys (v/metadata right-split) [:width :height :has-alpha?])))
          (is (= {:width 600 :height 600 :has-alpha? false}
                 (select-keys (v/metadata corner-split) [:width :height :has-alpha?])))
          (is (= {:width 600 :height 600 :has-alpha? false}
                 (select-keys (v/metadata square-limit) [:width :height :has-alpha?])))
          (is (< (:out (ops/avg george)) 255.0))
          (is (< (:out (ops/avg george4)) 255.0))
          (is (< (:out (ops/avg right-split)) 255.0))
          (is (< (:out (ops/avg corner-split)) 255.0))
          (is (< (:out (ops/avg square-limit)) 255.0))))
      (finally
        (cleanup-example-outputs! original-outputs)))))

(deftest funcgeo2-fish-example
  (let [original-outputs (capture-example-outputs)]
    (doseq [path [funcgeo2-01-fish-output-path
                  funcgeo2-02-fish-over-output-path
                  funcgeo2-03-t-tile-output-path
                  funcgeo2-04-u-tile-output-path
                  funcgeo2-05-side-output-path
                  funcgeo2-06-corner-output-path
                  funcgeo2-07-square-limit-output-path]]
      (fs/delete-if-exists path))
    (try
      (let [{:keys [exit out err]} (run-example! "examples/funcgeo2_fish.clj")]
        (is (zero? exit) (str out err))
        (is (str/includes? out "01-fish:"))
        (is (str/includes? out "02-fish-over:"))
        (is (str/includes? out "03-t-tile:"))
        (is (str/includes? out "04-u-tile:"))
        (is (str/includes? out "05-side:"))
        (is (str/includes? out "06-corner:"))
        (is (str/includes? out "07-square-limit:"))
        (with-open [fish         (v/from-file funcgeo2-01-fish-output-path)
                    fish-over    (v/from-file funcgeo2-02-fish-over-output-path)
                    t-tile       (v/from-file funcgeo2-03-t-tile-output-path)
                    u-tile       (v/from-file funcgeo2-04-u-tile-output-path)
                    side         (v/from-file funcgeo2-05-side-output-path)
                    corner       (v/from-file funcgeo2-06-corner-output-path)
                    square-limit (v/from-file funcgeo2-07-square-limit-output-path)]
          (is (= {:width 652 :height 448 :has-alpha? false}
                 (select-keys (v/metadata fish) [:width :height :has-alpha?])))
          (is (= {:width 652 :height 448 :has-alpha? false}
                 (select-keys (v/metadata fish-over) [:width :height :has-alpha?])))
          (is (= {:width 752 :height 648 :has-alpha? false}
                 (select-keys (v/metadata t-tile) [:width :height :has-alpha?])))
          (is (= {:width 852 :height 852 :has-alpha? false}
                 (select-keys (v/metadata u-tile) [:width :height :has-alpha?])))
          (is (= {:width 500 :height 512 :has-alpha? false}
                 (select-keys (v/metadata side) [:width :height :has-alpha?])))
          (is (= {:width 500 :height 512 :has-alpha? false}
                 (select-keys (v/metadata corner) [:width :height :has-alpha?])))
          (is (= {:width 1248 :height 1248 :has-alpha? false}
                 (select-keys (v/metadata square-limit) [:width :height :has-alpha?])))
          (is (< (:out (ops/avg fish)) 255.0))
          (is (< (:out (ops/avg fish-over)) 255.0))
          (is (< (:out (ops/avg t-tile)) 255.0))
          (is (< (:out (ops/avg u-tile)) 255.0))
          (is (< (:out (ops/avg side)) 255.0))
          (is (< (:out (ops/avg corner)) 255.0))
          (is (< (:out (ops/avg square-limit)) 255.0))))
      (finally
        (cleanup-example-outputs! original-outputs)))))
