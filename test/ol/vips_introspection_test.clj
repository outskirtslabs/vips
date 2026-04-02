(ns ol.vips-introspection-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ol.vips-test-common :as common]
   [ol.vips.introspect :as introspect]
   [ol.vips.runtime :as runtime]))

(deftest runtime-initialization
  (testing "the runtime can initialize the packaged libvips bundle"
    (let [state common/runtime-state]
      (is (= "8.17.3" (:version-string state)))
      (is (seq (:library-paths state)))
      (is (string? (:primary-library-path state))))))

(deftest operation-catalog
  (testing "operation discovery exposes the nicknames needed by the example API"
    (let [ops-set (set (introspect/list-operations))]
      (doseq [op ["thumbnail_image" "rotate" "colourspace" "flip" "join" "arrayjoin"]]
        (is (contains? ops-set op) (str "Missing operation nickname " op))))))

(deftest operation-description
  (testing "operation descriptions include enough type data to drive wrappers"
    (let [rotate      (introspect/describe-operation "rotate")
          flip        (introspect/describe-operation "flip")
          join        (introspect/describe-operation "arrayjoin")
          rotate-args (into {} (map (juxt :name identity) (:args rotate)))
          flip-args   (into {} (map (juxt :name identity) (:args flip)))
          join-args   (into {} (map (juxt :name identity) (:args join)))]
      (is (string? (:description rotate)))
      (is (= {:name       "in"
              :kind       :object
              :value-type "VipsImage"
              :input?     true
              :output?    false
              :required?  true}
             (select-keys (get rotate-args "in")
                          [:name :kind :value-type :input? :output? :required?])))
      (is (= {:name       "angle"
              :kind       :double
              :value-type "gdouble"
              :input?     true
              :required?  true}
             (select-keys (get rotate-args "angle")
                          [:name :kind :value-type :input? :required?])))
      (is (= {:name       "direction"
              :kind       :enum
              :value-type "VipsDirection"
              :input?     true
              :required?  true}
             (select-keys (get flip-args "direction")
                          [:name :kind :value-type :input? :required?])))
      (is (= {:name       "in"
              :kind       :boxed
              :value-type "VipsArrayImage"
              :input?     true
              :required?  true}
             (select-keys (get join-args "in")
                          [:name :kind :value-type :input? :required?]))))))

(deftest enum-codecs
  (testing "enum codecs provide friendly keyword roundtrips"
    (let [horizontal (introspect/encode-enum "VipsDirection" :horizontal)
          cmyk       (introspect/encode-enum "VipsInterpretation" :cmyk)]
      (is (integer? horizontal))
      (is (= :horizontal (introspect/decode-enum "VipsDirection" horizontal)))
      (is (integer? cmyk))
      (is (= :cmyk (introspect/decode-enum "VipsInterpretation" cmyk))))))

(deftest generic-image-operation-call
  (testing "generic operation calls can drive simple image transforms"
    (with-open [image   (runtime/open-image common/fixture-path)
                rotated (introspect/call-operation "rotate" {:in image :angle 90.0})
                flipped (introspect/call-operation "flip" {:in image :direction :horizontal})]
      (is (= {:width 3084 :height 2490 :has-alpha? false}
             (select-keys (runtime/image-info rotated) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (runtime/image-info flipped) [:width :height :has-alpha?]))))))
