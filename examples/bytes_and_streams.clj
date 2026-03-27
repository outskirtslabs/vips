(ns bytes-and-streams
  (:require
   [babashka.fs :as fs]
   [common :as common]
   [ol.vips :as v])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)))

(declare session)

(def output-path
  (common/output-path "rabbit_stream_bytes.png"))

(defn -main [& _]
  (common/ensure-output-dir!)
  (let [source-bytes (fs/read-all-bytes (common/dev-rabbit-path))
        final-bytes  (v/with-session [session]
                       (let [from-bytes    (v/open session source-bytes)
                             thumbnail     (v/thumbnail from-bytes 200)
                             png-bytes     (v/write! thumbnail :bytes {:format :png})
                             from-stream   (v/open session (ByteArrayInputStream. png-bytes))
                             output-stream (ByteArrayOutputStream.)]
                         (v/write! from-stream output-stream {:format :png})
                         (.toByteArray output-stream)))]
    (fs/write-bytes output-path final-bytes)
    (common/ensure! (= {:width 161 :height 200 :has-alpha? false}
                       (common/image-info output-path))
                    "Unexpected bytes/streams image dimensions"
                    {:path output-path})
    (common/print-result "bytes-and-streams" output-path)))

(-main)
