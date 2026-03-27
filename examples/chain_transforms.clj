(ns chain-transforms
  (:require
   [common :as common]
   [ol.vips :as v]))

(declare image)

(def output-path
  (common/output-path "rabbit_chain.jpg"))

(defn -main [& _]
  (common/ensure-output-dir!)
  (v/with-image [image (common/dev-rabbit-path)]
    (let [result (-> image
                     (v/thumbnail 400)
                     (v/invert)
                     (v/rotate 90)
                     (v/colourspace :cmyk)
                     (v/flip :horizontal))
          info   (v/image-info result)]
      (common/ensure! (= {:width 400 :height 323 :has-alpha? false}
                         (select-keys info [:width :height :has-alpha?]))
                      "Unexpected chained image dimensions"
                      {:info info})
      (v/write! result output-path)
      (common/print-result "chain" output-path))))

(-main)
