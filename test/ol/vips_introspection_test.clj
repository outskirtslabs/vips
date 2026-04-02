(ns ol.vips-introspection-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]))

(def ^:private missing ::missing)

(defn- resolve-var
  [sym]
  (try
    (requiring-resolve sym)
    (catch Throwable _
      nil)))

(defn- invoke
  [sym & args]
  (if-let [f (resolve-var sym)]
    (apply f args)
    missing))

(defn- rabbit-path
  []
  (str (fs/path "dev" "rabbit.jpg")))

(deftest runtime-initialization
  (testing "the runtime can initialize the packaged libvips bundle"
    (let [state (invoke 'ol.vips.runtime/ensure-initialized!)]
      (is (not= missing state) "Missing `ol.vips.runtime/ensure-initialized!`")
      (when (map? state)
        (is (= "8.17.3" (:version-string state)))
        (is (seq (:library-paths state)))
        (is (string? (:primary-library-path state)))))))

(deftest operation-catalog
  (testing "operation discovery exposes the nicknames needed by the example API"
    (let [ops (invoke 'ol.vips.introspect/list-operations)]
      (is (not= missing ops) "Missing `ol.vips.introspect/list-operations`")
      (when (sequential? ops)
        (let [ops-set (set ops)]
          (doseq [op ["thumbnail_image" "rotate" "colourspace" "flip" "join" "arrayjoin"]]
            (is (contains? ops-set op) (str "Missing operation nickname " op))))))))

(deftest operation-description
  (testing "operation descriptions include enough type data to drive wrappers"
    (let [describe-operation (resolve-var 'ol.vips.introspect/describe-operation)]
      (is describe-operation "Missing `ol.vips.introspect/describe-operation`")
      (when describe-operation
        (let [rotate      (describe-operation "rotate")
              flip        (describe-operation "flip")
              join        (describe-operation "arrayjoin")
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
                              [:name :kind :value-type :input? :required?]))))))))

(deftest enum-codecs
  (testing "enum codecs provide friendly keyword roundtrips"
    (let [encode-enum (resolve-var 'ol.vips.introspect/encode-enum)
          decode-enum (resolve-var 'ol.vips.introspect/decode-enum)]
      (is encode-enum "Missing `ol.vips.introspect/encode-enum`")
      (is decode-enum "Missing `ol.vips.introspect/decode-enum`")
      (when (and encode-enum decode-enum)
        (let [horizontal (encode-enum "VipsDirection" :horizontal)
              cmyk       (encode-enum "VipsInterpretation" :cmyk)]
          (is (integer? horizontal))
          (is (= :horizontal (decode-enum "VipsDirection" horizontal)))
          (is (integer? cmyk))
          (is (= :cmyk (decode-enum "VipsInterpretation" cmyk))))))))

(deftest generic-image-operation-call
  (testing "generic operation calls can drive simple image transforms"
    (let [ensure-initialized! (resolve-var 'ol.vips.runtime/ensure-initialized!)
          open-image          (resolve-var 'ol.vips.runtime/open-image)
          image-info          (resolve-var 'ol.vips.runtime/image-info)
          call-operation      (resolve-var 'ol.vips.introspect/call-operation)]
      (is ensure-initialized! "Missing `ol.vips.runtime/ensure-initialized!`")
      (is open-image "Missing `ol.vips.runtime/open-image`")
      (is image-info "Missing `ol.vips.runtime/image-info`")
      (is call-operation "Missing `ol.vips.introspect/call-operation`")
      (when (and ensure-initialized! open-image image-info call-operation)
        (ensure-initialized!)
        (with-open [image   (open-image (rabbit-path))
                    rotated (:out (call-operation "rotate" {:in image :angle 90.0}))
                    flipped (:out (call-operation "flip" {:in image :direction :horizontal}))]
          (is (= {:width 3084 :height 2490 :has-alpha? false}
                 (select-keys (image-info rotated) [:width :height :has-alpha?])))
          (is (= {:width 2490 :height 3084 :has-alpha? false}
                 (select-keys (image-info flipped) [:width :height :has-alpha?]))))))))
