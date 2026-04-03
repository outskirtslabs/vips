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
           (v/decode-enum "VipsDirection"
                          (v/encode-enum "VipsDirection" :horizontal)))))
  (testing "generated namespaces expose basic namespace docstrings"
    (is (re-find #"Generated enum descriptors"
                 (:doc (meta (find-ns 'ol.vips.enums)))))
    (is (re-find #"Generated libvips operation wrappers"
                 (:doc (meta (find-ns 'ol.vips.operations))))))
  (testing "generated enums expose useful docstrings"
    (is (re-find #"Lists the generated libvips enum ids"
                 (:doc (meta #'enums/enums))))
    (is (re-find #"Generated enum descriptor for `VipsDirection`"
                 (:doc (meta #'enums/direction))))))

(deftest generated-enum-source-formatting
  (testing "generated namespace header keeps ns name on the same line and docstring indented"
    (let [source (slurp "src/ol/vips/enums.clj")]
      (is (re-find #"\(ns ol\.vips\.enums\n  \"Generated enum descriptors for normalized libvips enum ids\." source))
      (is (not (re-find #"\(ns\s*\n\s+ol\.vips\.enums" source)))
      (is (re-find #"\n  Use \[\[registry\]\] or the generated enum vars" source)))
    (let [source (slurp "src/ol/vips/operations.clj")]
      (is (re-find #"\(ns ol\.vips\.operations\n  \"Generated libvips operation wrappers keyed by normalized operation id\." source))
      (is (not (re-find #"\(ns\s*\n\s+ol\.vips\.operations" source)))
      (is (re-find #"\n  Inspect \[\[registry\]\] directly for generated operation metadata" source))))
  (testing "generated enum vars keep the var name on the def line and indent docstrings cleanly"
    (let [source (slurp "src/ol/vips/enums.clj")]
      (is (re-find #"\(def access\n  \"Generated enum descriptor for `VipsAccess`\." source))
      (is (not (re-find #"\(def\s*\n\s+access" source)))
      (is (re-find #"\n  Use this value directly or look it up in \[\[registry\]\]" source))
      (is (re-find #"\n  Known values:\n  - `:random`\n  - `:sequential`" source)))))

(deftest generated-operation-surface
  (testing "generated operations are discoverable by normalized keyword names"
    (let [join      (get ops/registry :join)
          rotate    (get ops/registry :rotate)
          embed     (get ops/registry :embed)
          thumbnail (get ops/registry :thumbnail-image)]
      (is (= "join" (:operation-name join)))
      (is (= "rotate" (:operation-name rotate)))
      (is (= "thumbnail_image" (:operation-name thumbnail)))
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
      (is (re-find #"Avoid for routine thumbnailing"
                   (:doc (meta #'ops/thumbnail-image))))
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
