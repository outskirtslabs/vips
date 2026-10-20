(ns script.test-runtime
  (:require
   [clojure.test :as test]))

(def test-namespaces
  '[ol.vips-test
    ol.vips-generated-api-test
    ol.vips-introspection-test
    ol.vips-io-test
    ol.vips-runtime-portability-test])

(defn -main
  "Runs the shared runtime tests, or the namespace names in `names`."
  [& names]
  (let [namespaces (if (seq names) (mapv symbol names) test-namespaces)]
    (doseq [test-ns namespaces]
      (require test-ns))
    (let [{:keys [fail error] :as result} (apply test/run-tests namespaces)]
      (when (pos? (+ fail error))
        (throw (ex-info "Runtime tests failed" result)))
      result)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
