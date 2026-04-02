(ns ol.vips-generated-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ol.vips :as v]
   [ol.vips.enums :as enums]
   [ol.vips.operations :as ops]
   [ol.vips-test-common :as common]))

(deftest generated-enum-surface
  (testing "generated enums provide stable, discoverable codec data"
    (is (contains? (set (enums/enums)) :direction))
    (is (= "VipsDirection" (:type-name enums/direction)))
    (is (= :horizontal
           (enums/decode :direction
                         (enums/encode :direction :horizontal))))))

(deftest generated-operation-surface
  (testing "generated operations are discoverable by normalized keyword names"
    (let [generated (set (ops/operations))]
      (is (contains? generated :rotate))
      (is (contains? generated :join))
      (is (contains? generated :thumbnail-image))
      (is (= "join" (:operation-name (ops/describe :join))))
      (is (= "rotate" (:operation-name (ops/describe :rotate)))))))

(deftest generated-operations-return-output-maps
  (testing "generated wrappers preserve the full libvips output map contract"
    (with-open [image (v/image-from-file common/fixture-path)]
      (let [autorot-result (ops/autorot image)
            rotate-result  (ops/rotate image 90.0)]
        (is (= #{:out :angle :flip} (set (keys autorot-result))))
        (is (= #{:out} (set (keys rotate-result))))
        (is (keyword? (:angle autorot-result)))
        (is (boolean? (:flip autorot-result)))
        (with-open [autorot-image (:out autorot-result)
                    rotated       (:out rotate-result)
                    joined        (:out (ops/join image rotated :horizontal {:shim 10}))]
          (is (= {:width 2490 :height 3084 :has-alpha? false}
                 (select-keys (v/image-info autorot-image) [:width :height :has-alpha?])))
          (is (= {:width 3084 :height 2490 :has-alpha? false}
                 (select-keys (v/image-info rotated) [:width :height :has-alpha?])))
          (is (= {:width 5584 :height 2490 :has-alpha? false}
                 (select-keys (v/image-info joined) [:width :height :has-alpha?]))))))))

(deftest generated-array-operations-work
  (testing "generated boxed image-array operations delegate through call!"
    (with-open [a    (v/image-from-file common/fixture-path)
                b    (v/image-from-file common/fixture-path)
                c    (v/image-from-file common/fixture-path)
                d    (v/image-from-file common/fixture-path)
                grid (:out (ops/arrayjoin [a b c d]
                                          {:across 2
                                           :shim   10
                                           :halign :centre
                                           :valign :centre}))]
      (is (= {:width 4990 :height 6178 :has-alpha? false}
             (select-keys (v/image-info grid) [:width :height :has-alpha?]))))))
