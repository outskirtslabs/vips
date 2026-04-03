(ns http-stream-fetch
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def image-url
  "https://casey.link/square-flask.png")

(def output-path
  (fs/path "examples" "square_flask_http_stream.png"))

(def border-size 20)

(def border-color
  [231 98 39 255])

(defn -main [& _]
  (fs/create-dirs "examples")
  (with-open [response-body (:body (http/get image-url {:as :stream}))
              image         (v/from-stream response-body {:access  :sequential
                                                          :fail-on :error})
              thumbnail     (ops/thumbnail-image image 800 {:height  1000
                                                            :fail-on :error})
              bordered      (ops/embed thumbnail
                                       border-size
                                       border-size
                                       (+ (v/width thumbnail) (* 2 border-size))
                                       (+ (v/height thumbnail) (* 2 border-size))
                                       {:extend     :background
                                        :background border-color})]
    (v/write-to-file bordered output-path)
    (println (str "http-stream: " output-path))))

(-main)
