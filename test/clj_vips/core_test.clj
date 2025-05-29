(ns clj-vips.core-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clj-vips.core :as core]
   [clj-vips.api :as api]))

(deftest init-shutdown-test
  (testing "Can initialize and shutdown with default config"
    (core/init)
    (core/shutdown)))

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
    (core/shutdown)))
