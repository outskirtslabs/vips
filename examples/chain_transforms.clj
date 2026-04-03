(ns chain-transforms
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def output-path
  (fs/path "examples" "rabbit_chain.jpg"))

(def rabbit-path
  (fs/path "dev" "rabbit.jpg"))

(defn -main [& _]
  (fs/create-dirs "examples")
  (with-open [image  (v/from-file rabbit-path)
              result (-> image
                         (v/thumbnail 400)
                         (ops/invert)
                         (ops/rotate 90.0)
                         (ops/colourspace :cmyk)
                         (ops/flip :horizontal))]
    (v/write-to-file result output-path)
    (println (str "chain: " output-path))))

(-main)
