(ns create-thumbnail
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]))

(def output-path
  (fs/path "examples" "rabbit_thumbnail_400.jpg"))

(def rabbit-path
  (fs/path "dev" "rabbit.jpg"))

(defn -main [& _]
  (fs/create-dirs "examples")
  (with-open [image     (v/from-file rabbit-path)
              thumbnail (v/thumbnail image 400 {:auto-rotate true})]
    (v/write-to-file thumbnail output-path)
    (println (str "thumbnail: " output-path))))

(-main)
