(ns bytes-and-streams
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]))

(def output-path
  (fs/path "examples" "rabbit_stream_bytes.png"))

(def rabbit-path
  (fs/path "dev" "rabbit.jpg"))

(defn -main [& _]
  (fs/create-dirs "examples")
  (let [source-bytes  (fs/read-all-bytes rabbit-path)
        source-chunks (partition-all 4096 source-bytes)
        final-bytes   (with-open [from-chunks (v/from-chunks source-chunks)
                                  thumbnail   (v/thumbnail from-chunks 200)
                                  streamed    (v/from-chunks (v/write-to-stream thumbnail ".png"
                                                                                {:chunk-size 4096}))]
                        (v/write-to-buffer streamed ".png"))]
    (fs/write-bytes output-path final-bytes)
    (println (str "bytes-and-streams: " output-path))))

(-main)
