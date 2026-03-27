(ns compose-images
  (:require
   [common :as common]
   [ol.vips :as v]))

(declare session)

(def joined-output-path
  (common/output-path "rabbit_fox_joined.jpg"))

(def grid-output-path
  (common/output-path "rabbit_grid.jpg"))

(defn -main [& _]
  (common/ensure-output-dir!)
  (v/with-session [session]
    (let [rabbit      (-> (v/open session (common/dev-rabbit-path))
                          (v/thumbnail 500))
          fox         (-> (v/open session (common/sample-image-path "fox.jpg"))
                          (v/thumbnail 500))
          joined      (v/join rabbit fox {:direction :horizontal})
          grid        (v/array-join [(v/thumbnail rabbit 400)
                                     (v/thumbnail fox 400)
                                     (v/thumbnail fox 400)
                                     (v/thumbnail rabbit 400)]
                                    {:across 2
                                     :shim   10
                                     :halign :centre
                                     :valign :centre})
          joined-info (v/image-info joined)
          grid-info   (v/image-info grid)]
      (common/ensure! (= {:width 808 :height 500 :has-alpha? false}
                         (select-keys joined-info [:width :height :has-alpha?]))
                      "Unexpected joined image dimensions"
                      {:info joined-info})
      (common/ensure! (= {:width 656 :height 810 :has-alpha? false}
                         (select-keys grid-info [:width :height :has-alpha?]))
                      "Unexpected grid image dimensions"
                      {:info grid-info})
      (v/write! joined joined-output-path)
      (v/write! grid grid-output-path)
      (common/print-result "joined" joined-output-path)
      (common/print-result "grid" grid-output-path))))

(-main)
