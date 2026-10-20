(ns ol.vips.runtime-smoke-test
  (:require
   [clojure.test :refer [deftest is run-tests]]
   [ol.vips :as v]
   [ol.vips.operations :as ops])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]))

(deftest image-processing
  (is (= :packaged (:native-load-source (v/init!))))
  (with-open [black  (ops/black 32 24 {:bands 3})
              colour (ops/linear black [1] [42])
              small  (ops/resize colour 0.5)]
    (is (= [16 12 3] (v/shape small)))
    (with-open [average (ops/avg small)]
      (is (= 42.0 (:out average))))
    (v/write-to-file small "result.png")
    (with-open [file-image (v/from-file "result.png")
                average    (ops/avg file-image)]
      (is (= [16 12 3] (v/shape file-image)))
      (is (= 42.0 (:out average))))
    (let [encoded (v/write-to-buffer small ".png")
          output  (ByteArrayOutputStream.)]
      (with-open [stream-image (v/from-stream (ByteArrayInputStream. encoded))]
        (v/write-to-stream stream-image output ".png")
        (with-open [roundtrip (v/from-buffer (.toByteArray output))
                    average   (ops/avg roundtrip)]
          (is (= [16 12 3] (v/shape roundtrip)))
          (is (= 42.0 (:out average))))))
    (.close ^java.lang.AutoCloseable small)
    (.close ^java.lang.AutoCloseable small)
    (is (= :ol.vips/closed-image-handle
           (try
             (v/width small)
             (catch clojure.lang.ExceptionInfo e
               (:type (ex-data e))))))))

(defn -main
  "Checks the packaged image-processing interface in the current runtime."
  [& _]
  (let [{:keys [fail error] :as result} (run-tests 'ol.vips.runtime-smoke-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Packaged runtime smoke test failed" result)))))
