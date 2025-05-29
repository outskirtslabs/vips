(ns clj-vips.api-test
  (:require
   [coffi.mem :as mem]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-vips.api :as api]))

(def VIPS_VERSION [8 16 1])

(defn vips-fixture [f]
  (api/vips-init "clj-vips-test")
  (f)
  (api/vips-shutdown))

(use-fixtures :each vips-fixture)

(deftest basic-init-test
  (testing "Can initialize and shutdown libvips"
    #_{:clj-kondo/ignore [:type-mismatch]}
    (is (zero? (api/vips-init "clj-vips-test")) "vips_init should return 0 on success")))

(deftest error-buffer-test
  (testing "Can call vips-error-buffer"
    (is (string? (api/vips-error-buffer)) "Error buffer should return a string")))

(deftest version-test
  (testing "Can get libvips version components"
    (is (= (nth VIPS_VERSION 0) (api/vips-version 0)))
    (is (= (nth VIPS_VERSION 1) (api/vips-version 1)))
    (is (= (nth VIPS_VERSION 2) (api/vips-version 2)))))

(deftest version-string-test
  (testing "Can get libvips version string"
    (is (= (str/join "." VIPS_VERSION) (api/vips-version-string)))))

(deftest concurrency-test
  (testing "Can set and get concurrency level"
    (api/vips-concurrency-set 2)
    (is (= 2 (api/vips-concurrency-get)))))

(deftest cache-config-test
  (testing "Can configure cache settings"
    (api/vips-cache-set-max 75)
    (is (= 75 (api/vips-cache-get-max)))
    (api/vips-cache-set-max-files 10)
    (is (= 10 (api/vips-cache-get-max-files)))))

(deftest leak-detection-test
  (testing "Can set leak detection"
    (api/vips-leak-set 1)
    (api/vips-leak-set 0)
    (is true "we did not throw")))

(deftest cache-operations-test
  (testing "Cache operations work"
    (api/vips-cache-drop-all)
    (api/vips-cache-set-trace 1)
    (api/vips-cache-set-trace 0)
    (is true "we did not throw")))

(deftest image-constructor
  (testing "from nothing"
    (let [im (api/image-new-memory)]
      (try
        (api/image-write-to-file im "test.png" nil)
        (api/g_object_unref im)
        (is (.exists (io/file "test.png")))
        (finally
          (io/delete-file (io/file "test.png"))))))

  (testing "from non existing"
    (let [im (api/image-new-from-file "does-not-exist.png" nil)]
      (is (mem/null? im))))

  (testing "from existing"
    (try
      (let [orig-path "test/clj_vips/fixtures/clojure.png"
            copy-path "test/clj_vips/fixtures/clojure_copy.png"
            im        (api/image-new-from-file orig-path nil)]
        (is (not (mem/null? im)))
        (api/image-write-to-file im copy-path nil)
        (api/g_object_unref im)
        (is (= (.length (io/file orig-path))
               (.length (io/file copy-path)))))
      (finally
        (io/delete-file (io/file "test/clj_vips/fixtures/clojure_copy.png"))))))
