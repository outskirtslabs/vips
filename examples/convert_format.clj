(ns convert-format
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]))

(def output-path
  (fs/path "examples" "alpha_band.jpg"))

(def png-path
  (fs/path "test" "fixtures" "alpha_band.png"))

(defn -main [& _]
  (fs/create-dirs "examples")
  (with-open [image (v/from-file png-path)]
    (v/write-to-file image output-path)
    (println (str "jpeg: " output-path))))

(-main)
