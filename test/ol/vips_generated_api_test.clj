(ns ol.vips-generated-api-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [ol.vips :as v]
   [ol.vips.enums :as enums]
   [ol.vips.operations :as ops]))

(def fixture-path
  (str (fs/path "dev" "rabbit.jpg")))

(deftest generated-enum-surface
  (testing "generated enums provide stable, discoverable codec data"
    (is (contains? (set (enums/enums)) :direction))
    (is (= "VipsDirection" (:type-name enums/direction)))
    (is (= :horizontal
           (enums/decode :direction
                         (enums/encode :direction :horizontal))))))

(deftest generated-operation-surface
  (testing "generated operations are discoverable by normalized keyword names"
    (let [generated (set (ops/operations))
          join      (ops/describe :join)
          rotate    (ops/describe :rotate)
          embed     (ops/describe :embed)]
      (is (contains? generated :rotate))
      (is (contains? generated :join))
      (is (contains? generated :thumbnail-image))
      (is (= "join" (:operation-name join)))
      (is (= "rotate" (:operation-name rotate)))
      (is (= {:kind :image :label "image"}
             (:type (first (:required-inputs rotate)))))
      (is (= {:kind :float :label "float"}
             (:type (second (:required-inputs rotate)))))
      (is (= {:kind      :enum
              :label     "keyword"
              :enum-id   :direction
              :reference "ol.vips.enums/direction"}
             (:type (nth (:required-inputs join) 2))))
      (is (= {:kind :float-seq :label "seqable of number"}
             (:type (some #(when (= "background" (:name %)) %)
                          (:optional-inputs embed)))))
      (is (not (re-find #"gdouble|VipsImage|gint"
                        (:doc (meta #'ops/rotate))))))))

(deftest generated-operations-return-images-or-output-maps
  (testing "single-image wrappers return image handles directly"
    (with-open [image   (v/from-file fixture-path)
                rotated (ops/rotate image 90.0)
                joined  (ops/join image rotated :horizontal {:shim 10})]
      (is (= {:width 3084 :height 2490 :has-alpha? false}
             (select-keys (v/metadata rotated) [:width :height :has-alpha?])))
      (is (= {:width 5584 :height 2490 :has-alpha? false}
             (select-keys (v/metadata joined) [:width :height :has-alpha?])))))
  (testing "multi-output wrappers preserve additional outputs in a closeable result"
    (with-open [image   (v/from-file fixture-path)
                autorot (ops/autorot image)]
      (is (= #{:out :angle :flip} (set (keys autorot))))
      (is (keyword? (:angle autorot)))
      (is (boolean? (:flip autorot)))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/metadata autorot) [:width :height :has-alpha?]))))))

(deftest generated-array-operations-work
  (testing "generated boxed image-array operations delegate through call"
    (with-open [a    (v/from-file fixture-path)
                b    (v/from-file fixture-path)
                c    (v/from-file fixture-path)
                d    (v/from-file fixture-path)
                grid (ops/arrayjoin [a b c d]
                                    {:across 2
                                     :shim   10
                                     :halign :centre
                                     :valign :centre})]
      (is (= {:width 4990 :height 6178 :has-alpha? false}
             (select-keys (v/metadata grid) [:width :height :has-alpha?]))))))

(deftest generated-operations-accept-result-maps
  (testing "generated wrappers accept prior operation result maps as image inputs"
    (with-open [image (v/from-file fixture-path)]
      (with-open [autorot (ops/autorot image)
                  flipped (ops/flip autorot :horizontal)
                  joined  (ops/join autorot flipped :horizontal)]
        (is (= {:width 4980 :height 3084 :has-alpha? false}
               (select-keys (v/metadata joined) [:width :height :has-alpha?])))))))
