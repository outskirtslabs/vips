(ns ol.vips-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ol.vips :as v]
   [ol.vips-test-common :as common]))

(deftest public-operation-surface
  (testing "the public namespace exposes the minimal low-level API"
    (let [state common/runtime-state
          ops   (set (v/operations))
          flip  (v/operation-info "flip")]
      (is (= "8.17.3" (:version-string state)))
      (is (contains? ops "rotate"))
      (is (contains? ops "arrayjoin"))
      (is (= :horizontal
             (v/decode-enum "VipsDirection"
                            (v/encode-enum "VipsDirection" :horizontal))))
      (is (= "flip" (:name flip))))))

(deftest open-and-image-info
  (testing "image-from-file accepts path strings and Path values"
    (with-open [from-string (v/image-from-file common/fixture-path)
                from-path   (v/image-from-file (java.nio.file.Path/of common/fixture-path
                                                                      (make-array String 0)))]
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info from-string) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info from-path) [:width :height :has-alpha?]))))))

(deftest transforms
  (testing "rotate, colourspace, and flip compose through call!"
    (with-open [image   (v/image-from-file common/fixture-path)
                rotated (:out (v/call! "rotate" {:in image :angle 90.0}))
                bw      (:out (v/call! "colourspace" {:in image :space :b-w}))
                flipped (:out (v/call! "flip" {:in image :direction :horizontal}))]
      (is (= {:width 3084 :height 2490 :has-alpha? false}
             (select-keys (v/image-info rotated) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info bw) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info flipped) [:width :height :has-alpha?])))))
  (testing "invalid enum keywords fail before entering libvips"
    (with-open [image (v/image-from-file common/fixture-path)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown enum value"
                            (v/call! "flip" {:in image :direction :sideways})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown enum value"
                            (v/call! "colourspace" {:in image :space :sepia}))))))

(deftest join-and-array-join
  (testing "join composes two images through the raw operation contract"
    (with-open [left   (v/image-from-file common/fixture-path)
                right  (v/image-from-file common/fixture-path)
                joined (:out (v/call! "join" {:in1       left
                                              :in2       right
                                              :direction :horizontal}))]
      (is (= {:width 4980 :height 3084 :has-alpha? false}
             (select-keys (v/image-info joined) [:width :height :has-alpha?])))))
  (testing "arrayjoin accepts a collection of images plus layout options"
    (with-open [a    (v/image-from-file common/fixture-path)
                b    (v/image-from-file common/fixture-path)
                c    (v/image-from-file common/fixture-path)
                d    (v/image-from-file common/fixture-path)
                grid (:out (v/call! "arrayjoin" {:in     [a b c d]
                                                 :across 2
                                                 :shim   10
                                                 :halign :centre
                                                 :valign :centre}))]
      (is (= {:width 4990 :height 6178 :has-alpha? false}
             (select-keys (v/image-info grid) [:width :height :has-alpha?]))))))
