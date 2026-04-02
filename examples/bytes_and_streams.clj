(ns bytes-and-streams
  (:require
   [babashka.fs :as fs]
   [common :as common]
   [ol.vips :as v]))

(def output-path
  (common/output-path "rabbit_stream_bytes.png"))

(defn -main [& _]
  (common/ensure-output-dir!)
  (let [source-bytes  (fs/read-all-bytes (common/dev-rabbit-path))
        source-chunks (partition-all 4096 source-bytes)
        final-bytes   (with-open [from-chunks (v/from-enum source-chunks)
                                  thumbnail   (v/thumbnail from-chunks 200)
                                  streamed    (v/from-enum (v/write-to-stream thumbnail ".png"
                                                                              {:chunk-size 4096}))]
                        (v/write-to-buffer streamed ".png"))]
    (fs/write-bytes output-path final-bytes)
    (common/ensure! (= {:width 161 :height 200 :has-alpha? false}
                       (common/image-info output-path))
                    "Unexpected bytes/streams image dimensions"
                    {:path output-path})
    (common/print-result "bytes-and-streams" output-path)))

(-main)
