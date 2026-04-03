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

(defn- run-example!
  [script-path]
  (shell/sh "clojure" "-M:dev" script-path))

(defn- capture-example-outputs
  []
  (into {}
        (map (fn [path]
               [path (when (fs/exists? path)
                       (java.nio.file.Files/readAllBytes path))])
             [metadata-output-path animated-output-path])))

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
    (doseq [path [metadata-output-path animated-output-path]]
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
      (finally
        (cleanup-example-outputs! original-outputs)))))
