(ns ol.vips.native-image-smoke
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests]]
   [ol.vips :as v]
   [ol.vips.runtime-smoke-test])
  (:gen-class))

(deftest native-runtime
  (is (= "runtime" (System/getProperty "org.graalvm.nativeimage.imagecode")))
  (is (nil? (io/resource "ol/vips.clj")))
  (is (nil? (io/resource "ol/vips/impl/handles.clj")))
  (let [state      (v/init!)
        cache-root (System/getProperty "ol.vips.native.cache-root")]
    (is (and cache-root (str/starts-with? (:primary-library-path state) cache-root)))))

(defn -main
  [& _]
  (let [{:keys [fail error]} (run-tests 'ol.vips.native-image-smoke 'ol.vips.runtime-smoke-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
