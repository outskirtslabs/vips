(ns create-thumbnail
  (:require
   [common :as common]
   [ol.vips :as v]))

(declare image)

(def output-path
  (common/output-path "rabbit_thumbnail_400.jpg"))

(defn -main [& _]
  (common/ensure-output-dir!)
  (v/with-image [image (common/dev-rabbit-path)]
    (let [thumbnail (v/thumbnail image 400 {:auto-rotate true})
          info      (v/image-info thumbnail)]
      (common/ensure! (= {:width 323 :height 400 :has-alpha? false}
                         (select-keys info [:width :height :has-alpha?]))
                      "Unexpected thumbnail dimensions"
                      {:info info})
      (v/write! thumbnail output-path)
      (common/print-result "thumbnail" output-path))))

(-main)
