(ns metadata-roundtrip
  (:require
   [common :as common]
   [ol.vips :as v]))

(declare session)

(def output-path
  (common/output-path "rabbit_metadata_copy.jpg"))

(defn -main [& _]
  (common/ensure-output-dir!)
  (v/with-session [session]
    (let [image          (v/open session (common/dev-rabbit-path))
          blob-value     (byte-array [(byte 1) (byte 2) (byte 3) (byte 4)])
          updated        (v/set-metadata image {:example-string "rabbit"
                                                :example-int    42
                                                :example-double 3.5
                                                :example-blob   blob-value
                                                :example-image  image
                                                :example-remove "remove-me"})
          metadata-image (v/metadata updated :example-image)
          selected       (v/metadata updated [:example-string :example-int :example-double :example-blob])]
      (common/ensure! (= "rabbit" (v/metadata updated :example-string))
                      "String metadata roundtrip failed"
                      {})
      (common/ensure! (= 42 (v/metadata updated :example-int))
                      "Int metadata roundtrip failed"
                      {})
      (common/ensure! (= 3.5 (v/metadata updated :example-double))
                      "Double metadata roundtrip failed"
                      {})
      (common/ensure! (common/bytes= blob-value (v/metadata updated :example-blob))
                      "Blob metadata roundtrip failed"
                      {})
      (common/ensure! (= {:width 2490 :height 3084 :has-alpha? false}
                         (select-keys (v/image-info metadata-image) [:width :height :has-alpha?]))
                      "Image metadata roundtrip failed"
                      {})
      (common/ensure! (= "rabbit" (:example-string selected))
                      "Selected metadata read failed"
                      {:selected selected})
      (common/ensure! (common/bytes= blob-value (:example-blob selected))
                      "Selected blob metadata read failed"
                      {:selected selected})
      (v/remove-metadata updated :example-remove)
      (common/ensure! (not (contains? (set (v/metadata-fields updated)) :example-remove))
                      "Metadata removal failed"
                      {})
      (v/write! updated output-path)
      (println "metadata ok")
      (common/print-result "metadata" output-path))))

(-main)
