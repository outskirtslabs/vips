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
  (with-open [image  (v/from-file rabbit-path)
              tagged (-> image
                         (v/assoc-field "xres" 10.0)
                         (v/assoc-field "yres" 10.0))]
    (println "selected metadata fields:")
    (println (select-keys (v/headers tagged)
                          ["width" "height" "xres" "yres"]))
    (v/write-to-file tagged output-path {:strip false})
    (println (str "metadata: " output-path))))

(-main)
