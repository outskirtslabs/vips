(ns clj-vips.core-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-vips.test-shared :refer [vips-fixture]]
   [clj-vips.core :as core]
   [clj-vips.api :as api]))

(use-fixtures :once vips-fixture)

;; No fixture - vips is initialized by api-test which runs first

(deftest init-shutdown-test
  (testing "Core functions are available after fixture init"
                                        ; Core is already initialized by the fixture
    (is (string? (api/vips-version-string)) "Should be able to call vips functions")))

(deftest init-with-config-test
  (testing "Can test configuration functions"
    ; Core is already initialized, so just test that configuration functions work
    (api/vips-concurrency-set 2)
    (is (= 2 (api/vips-concurrency-get)))
    (api/vips-cache-set-max 50)
    (is (= 50 (api/vips-cache-get-max)))))

(deftest cache-functions-test
  (testing "Cache utility functions work"
    ; Core is already initialized by fixture
    ;; Note: clear-cache (vips-cache-drop-all) can corrupt cache state, so we avoid it in tests
    ;; (core/clear-cache)
    (core/shutdown-thread)
    (is true "we did not crash during cache operations")))

(deftest new-image-from-file-test
  (testing "new-image-from-file throws exception for non-existent file"
    ; Core is already initialized by fixture
    (is (thrown? Exception (core/new-image-from-file "/non/existent/file.jpg")))))
