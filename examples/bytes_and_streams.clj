(ns bytes-and-streams
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def output-path
  (fs/path "examples" "rabbit_stream_bytes.png"))

(def rabbit-path
  (fs/path "dev" "rabbit.jpg"))

(defn -main [& _]
  (fs/create-dirs "examples")
  (let [source-bytes (fs/read-all-bytes rabbit-path)
        final-bytes  (with-open [in        (ByteArrayInputStream. source-bytes)
                                 source    (v/from-stream in)
                                 thumbnail (v/thumbnail source 200)
                                 out       (ByteArrayOutputStream.)]
                       (v/write-to-stream thumbnail out ".png")
                       (.toByteArray out))]
    (fs/write-bytes output-path final-bytes)
    (println (str "bytes-and-streams: " output-path))))

(-main)
