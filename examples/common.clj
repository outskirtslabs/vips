(ns common
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v])
  (:import
   (java.util Arrays)))

(declare image)

(def project-root
  (fs/absolutize "."))

(def output-root
  (fs/path project-root "examples"))

(defn ensure-output-dir! []
  (fs/create-dirs output-root))

(defn output-path [filename]
  (fs/path output-root filename))

(defn sample-image-path [filename]
  (fs/path project-root "extra" "vips-ffm" "sample" "src" "main" "resources" "sample_images" filename))

(defn dev-rabbit-path []
  (fs/path project-root "dev" "rabbit.jpg"))

(defn bytes= [left right]
  (Arrays/equals ^bytes left ^bytes right))

(defn image-info [path]
  (with-open [image (v/from-file path)]
    (select-keys (v/image-info image) [:width :height :has-alpha?])))

(defn ensure! [pred message data]
  (when-not pred
    (throw (ex-info message data))))

(defn print-result [label path]
  (println (str label ": " path)))
