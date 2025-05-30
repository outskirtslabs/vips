(ns clj-vips.core-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clj-vips.core :as core]
   [clj-vips.api :as api]))

(deftest init-shutdown-test
  (testing "Can initialize and shutdown with default config"
    (core/init)
    (core/shutdown)
    (is true "we did not crash during init/shutdown")))

(deftest init-with-config-test
  (testing "Can initialize with custom configuration"
    (core/init {:concurrency-level 2
                :max-cache-size    50
                :report-leaks?     true})
    (is (= 2 (api/vips-concurrency-get)))
    (is (= 50 (api/vips-cache-get-max)))
    (core/shutdown)))

(deftest cache-functions-test
  (testing "Cache utility functions work"
    (core/init)
    (core/clear-cache)
    (core/shutdown-thread)
    (core/shutdown)
    (is true "we did not crash during cache operations")))

(deftest new-image-from-file-test
  (testing "new-image-from-file throws exception for non-existent file"
    (core/init)
    (try
      (is (thrown? Exception (core/new-image-from-file "/non/existent/file.jpg")))
      (finally
        (core/shutdown)))))
