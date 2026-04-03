(ns metadata-roundtrip
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]))

(def output-path
  (fs/path "examples" "rabbit_metadata_copy.jpg"))

(def rabbit-path
  (fs/path "dev" "rabbit.jpg"))

(defn -main [& _]
  (fs/create-dirs "examples")
  (with-open [image (v/from-file rabbit-path)]
    (v/write-to-file image output-path {:strip false})
    (println "metadata API note: copy/save works, but metadata read/write helpers are not currently exposed")
    (println (str "metadata: " output-path))))

(-main)
