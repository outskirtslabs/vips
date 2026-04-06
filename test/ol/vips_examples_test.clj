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
                    dominant-palette-output-path]]
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
