(ns clj-vips.api-test
  (:require
   [coffi.mem :as mem]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-vips.api :as api]
   [clj-vips.test-shared :refer [vips-fixture]]
   [clj-vips.api.operation :as op]))

(def VIPS_VERSION [8 16 1])

(use-fixtures :once vips-fixture)

(deftest basic-init-test
  (testing "Vips is initialized and working"
                                        ; Vips is already initialized by the fixture, so just test a basic function
    (is (string? (api/vips-version-string)) "Should be able to call vips functions")))

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
    ;; Note: vips-cache-drop-all can corrupt cache state, so we avoid it in tests
    ;; (api/vips-cache-drop-all) 
    (api/vips-cache-set-trace 1)
    (api/vips-cache-set-trace 0)
    (is true "we did not throw")))

(deftest image-constructor
  (testing "from nothing"
    (let [im (api/image-new-memory)]
      (try
        (api/image-write-to-file im "test.png" nil)
        (api/g-object-unref im)
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
            im (api/image-new-from-file orig-path nil)]
        (is (not (mem/null? im)))
        (api/image-write-to-file im copy-path nil)
        (api/g-object-unref im)
        (is (= (.length (io/file orig-path))
               (.length (io/file copy-path)))))
      (finally
        (io/delete-file (io/file "test/clj_vips/fixtures/clojure_copy.png"))))))

(deftest operation-introspection-test
  (testing "Can discover operation arguments"
    (let [op (api/operation-new "invert")]
      (try
        (let [args-info (op/discover-operation-arguments op)]
          (is (vector? args-info) "Should return a vector of argument info")
          (is (pos? (count args-info)) "Should have at least one argument")
          (let [in-arg (first (filter #(= (:name %) "in") args-info))]
            (is (some? in-arg) "Should have 'in' argument")
            (is (:input? in-arg) "in argument should be marked as input")))
        (finally
          (api/g-object-unref op)))))

  (testing "Can set operation arguments with proper types"
    (let [input-path "test/clj_vips/fixtures/clojure.png"
          input-image (api/image-new-from-file input-path nil)
          op (api/operation-new "invert")]
      (try
        (let [args-info (op/discover-operation-arguments op)
              args-map (into {} (map (juxt :name identity) args-info))
              in-arg (get args-map "in")]
          (is (some? in-arg) "Should find 'in' argument")
          (op/set-argument op in-arg input-image)
          (is true "Setting argument should not throw"))
        (finally
          (api/g-object-unref op)
          (api/g-object-unref input-image))))))

(deftest call-operation-test
  (testing "Can call invert operation"
    (let [input-path "test/clj_vips/fixtures/clojure.png"
          input-image (api/image-new-from-file input-path nil)]
      (try
        (let [inverted (op/call-operation "invert" {:in input-image})]
          (is (not (mem/null? inverted)) "Invert should return non-null image")
          (let [output-path "test-invert-output.png"]
            (try
              (api/image-write-to-file inverted output-path mem/null)
              (is (.exists (io/file output-path)) "Output file should be created")
              (finally
                (io/delete-file (io/file output-path) true)))
            (api/g-object-unref inverted)))
        (finally
          (api/g-object-unref input-image)))))

  (testing "Can call resize operation with scale parameters"
    (let [input-path "test/clj_vips/fixtures/clojure.png"
          input-image (api/image-new-from-file input-path nil)]
      (try
        (let [resized (op/call-operation "resize" {:in input-image :scale 2.0 :vscale 3.0})]
          (is (not (mem/null? resized)) "Resize should return non-null image")
          (let [output-path "test-resize-output.png"]
            (try
              (api/image-write-to-file resized output-path mem/null)
              (is (.exists (io/file output-path)) "Output file should be created")
              (finally
                (io/delete-file (io/file output-path) true)))
            (api/g-object-unref resized)))
        (finally
          (api/g-object-unref input-image)))))

  (testing "Can call thumbnail operation with enum keyword"
    (let [input-path "test/clj_vips/fixtures/puppies.jpg"
          thumbnail (op/call-operation "thumbnail" {:filename input-path
                                                    :crop :interesting/attention
                                                    :width 100})]
      (is (not (mem/null? thumbnail)) "Thumbnail should return non-null image")
      (let [output-path "test-thumbnail-enum.png"]
        (try
          (api/image-write-to-file thumbnail output-path mem/null)
          (is (.exists (io/file output-path)) "Output file should be created")
          (finally
            (io/delete-file (io/file output-path) true)))
        (api/g-object-unref thumbnail))))

  (testing "Throws error for unknown operation"
    (is (thrown-with-msg? Exception #"Unknown argument"
                          (op/call-operation "invert" {:nonexistent-arg "value"}))))

  (testing "Throws error for invalid operation name"
    (is (thrown? Exception
                 (op/call-operation "nonexistent-operation" {})))))

(deftest enum-keyword-test
  (testing "Keyword to enum value conversion"
    (is (= 0 (op/keyword->enum-value :interesting/none)))
    (is (= 1 (op/keyword->enum-value :interesting/centre)))
    (is (= 2 (op/keyword->enum-value :interesting/entropy)))
    (is (= 3 (op/keyword->enum-value :interesting/attention)))
    (is (= 4 (op/keyword->enum-value :interesting/low)))
    (is (= 5 (op/keyword->enum-value :interesting/high)))
    (is (= 6 (op/keyword->enum-value :interesting/all))))

  (testing "Unknown enum keyword throws error"
    (is (thrown-with-msg? Exception #"Unknown enum keyword"
                          (op/keyword->enum-value :invalid/keyword)))))
