(ns ol.vips-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [ol.vips :as v])
  (:import
   (clojure.lang ExceptionInfo)
   (java.io ByteArrayInputStream ByteArrayOutputStream)))

(declare session image output-image left-session right-session)

(def fixture-path "dev/rabbit.jpg")

(defn fixture-bytes []
  (fs/read-all-bytes fixture-path))

(defn temp-file-path
  ([] (temp-file-path ".jpg"))
  ([suffix]
   (fs/create-temp-file {:prefix "ol-vips-test-"
                         :suffix suffix})))

(defn path-exists? [path]
  (fs/exists? path))

(defn byte-array? [value]
  (= (Class/forName "[B") (class value)))

(defn bytes= [left right]
  (java.util.Arrays/equals ^bytes left ^bytes right))

(deftest public-api-smoke
  (testing "the public namespace loads"
    (is (some? (find-ns 'ol.vips)))))

(deftest open-and-image-info
  (testing "open accepts path strings and Path values inside with-session"
    (v/with-session [session]
      (let [from-string (v/open session fixture-path)
            from-path   (v/open session (fs/path fixture-path))]
        (is (= {:width 2490 :height 3084 :has-alpha? false}
               (select-keys (v/image-info from-string) [:width :height :has-alpha?])))
        (is (= {:width 2490 :height 3084 :has-alpha? false}
               (select-keys (v/image-info from-path) [:width :height :has-alpha?]))))))
  (testing "open accepts an existing image handle in the same session"
    (v/with-session [session]
      (let [image    (v/open session fixture-path)
            reopened (v/open session image)]
        (is (= (select-keys (v/image-info image) [:width :height :has-alpha?])
               (select-keys (v/image-info reopened) [:width :height :has-alpha?])))))))

(deftest with-image-smoke
  (testing "with-image opens, transforms, and writes a real file"
    (let [output (temp-file-path ".jpg")]
      (v/with-image [image fixture-path]
        (let [result (-> image
                         (v/thumbnail 400 {:auto-rotate true})
                         (v/write! output))]
          (is (some? result))))
      (is (path-exists? output))
      (v/with-image [output-image output]
        (is (= {:width 323 :height 400 :has-alpha? false}
               (select-keys (v/image-info output-image) [:width :height :has-alpha?])))))))

(deftest transforms
  (testing "thumbnail, invert, rotate, colourspace, and flip compose over an image handle"
    (v/with-image [image fixture-path]
      (is (= {:width 323 :height 400 :has-alpha? false}
             (select-keys (v/image-info (v/thumbnail image 400 {:auto-rotate true}))
                          [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info (v/invert image)) [:width :height :has-alpha?])))
      (is (= {:width 3084 :height 2490 :has-alpha? false}
             (select-keys (v/image-info (v/rotate image 90)) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info (v/colourspace image :bw)) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info (v/flip image :horizontal)) [:width :height :has-alpha?])))))
  (testing "invalid enum keywords fail before entering libvips"
    (v/with-image [image fixture-path]
      (let [direction-error (is (thrown? ExceptionInfo (v/flip image :sideways)))
            space-error     (is (thrown? ExceptionInfo (v/colourspace image :sepia)))]
        (is direction-error)
        (is space-error)))))

(deftest join-and-array-join
  (testing "join defaults to horizontal composition inside one session"
    (v/with-session [session]
      (let [left   (v/open session fixture-path)
            right  (v/open session fixture-path)
            joined (v/join left right)]
        (is (= {:width 4980 :height 3084 :has-alpha? false}
               (select-keys (v/image-info joined) [:width :height :has-alpha?]))))))
  (testing "array-join respects across and shim options"
    (v/with-session [session]
      (let [images (repeatedly 4 #(v/open session fixture-path))
            joined (v/array-join images {:across 2 :shim 10 :halign :centre :valign :centre})]
        (is (= {:width 4990 :height 6178 :has-alpha? false}
               (select-keys (v/image-info joined) [:width :height :has-alpha?]))))))
  (testing "mixing image handles from different sessions throws useful ex-info"
    (let [error (try
                  (v/with-session [left-session]
                    (let [left (v/open left-session fixture-path)]
                      (v/with-session [right-session]
                        (let [right (v/open right-session fixture-path)]
                          (v/join left right)))))
                  (catch ExceptionInfo ex
                    ex))]
      (is (instance? ExceptionInfo error))
      (is (= :join (:op (ex-data error)))))))

(deftest io-round-trips
  (testing "open accepts byte arrays and InputStreams"
    (let [bytes (fixture-bytes)]
      (v/with-session [session]
        (let [from-bytes  (v/open session bytes)
              from-stream (v/open session (ByteArrayInputStream. bytes))]
          (is (= {:width 2490 :height 3084 :has-alpha? false}
                 (select-keys (v/image-info from-bytes) [:width :height :has-alpha?])))
          (is (= {:width 2490 :height 3084 :has-alpha? false}
                 (select-keys (v/image-info from-stream) [:width :height :has-alpha?])))))))
  (testing "write! writes to a path and returns the same image handle"
    (v/with-image [image fixture-path]
      (let [output   (temp-file-path ".png")
            returned (v/write! image output)]
        (is (identical? image returned))
        (is (path-exists? output)))))
  (testing "write! writes to an OutputStream when format is explicit"
    (v/with-image [image fixture-path]
      (let [output   (ByteArrayOutputStream.)
            returned (v/write! image output {:format :png})]
        (is (identical? image returned))
        (is (pos? (.size output))))))
  (testing "write! returns bytes when requested explicitly"
    (v/with-image [image fixture-path]
      (let [output (v/write! image :bytes {:format :jpg :q 80})]
        (is (byte-array? output))
        (is (pos? (alength output))))))
  (testing "write! requires :format for stream and byte sinks"
    (v/with-image [image fixture-path]
      (is (thrown? ExceptionInfo (v/write! image (ByteArrayOutputStream.))))
      (is (thrown? ExceptionInfo (v/write! image :bytes))))))

(deftest metadata-round-trips
  (testing "metadata fields, reads, writes, and removals round trip supported types"
    (v/with-image [image fixture-path]
      (let [blob-value   (byte-array [(byte 1) (byte 2) (byte 3)])
            updated      (v/set-metadata image {:ol-vips-test-string "hello"
                                                :ol-vips-test-int    42
                                                :ol-vips-test-double 3.5
                                                :ol-vips-test-blob   blob-value})
            fields       (set (v/metadata-fields updated))
            all-metadata (v/metadata updated)
            selected     (v/metadata updated [:ol-vips-test-string :ol-vips-test-int :ol-vips-test-double :ol-vips-test-blob])]
        (is (contains? fields :ol-vips-test-string))
        (is (contains? fields :ol-vips-test-int))
        (is (= "hello" (v/metadata updated :ol-vips-test-string)))
        (is (= 42 (v/metadata updated :ol-vips-test-int)))
        (is (= 3.5 (v/metadata updated :ol-vips-test-double)))
        (is (bytes= blob-value (v/metadata updated :ol-vips-test-blob)))
        (is (= "hello" (:ol-vips-test-string all-metadata)))
        (is (= "hello" (:ol-vips-test-string selected)))
        (is (= 42 (:ol-vips-test-int selected)))
        (is (= 3.5 (:ol-vips-test-double selected)))
        (is (bytes= blob-value (:ol-vips-test-blob selected)))
        (let [without-fields (v/remove-metadata updated [:ol-vips-test-string :ol-vips-test-int])]
          (is (not (contains? (set (v/metadata-fields without-fields)) :ol-vips-test-string)))
          (is (not (contains? (set (v/metadata-fields without-fields)) :ol-vips-test-int)))))))
  (testing "image-valued metadata returns an image handle in the same session"
    (v/with-session [session]
      (let [image          (v/open session fixture-path)
            updated        (v/set-metadata image {:ol-vips-test-image image})
            metadata-image (v/metadata updated :ol-vips-test-image)]
        (is (= {:width 2490 :height 3084 :has-alpha? false}
               (select-keys (v/image-info metadata-image) [:width :height :has-alpha?])))))))
