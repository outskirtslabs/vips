(ns ol.vips-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ol.vips :as v]
   [ol.vips.impl.api :as api]
   [ol.vips.operations :as ops])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream IOException InputStream OutputStream]))

(def fixture-path
  (str (fs/path "dev" "rabbit.jpg")))

(def fixture-root
  (fs/path "test" "fixtures"))

(def puppies-path
  (str (fs/path fixture-root "puppies.jpg")))

(def alpha-band-path
  (str (fs/path fixture-root "alpha_band.png")))

(def animated-gif-path
  (str (fs/path fixture-root "cogs.gif")))

(defonce runtime-state
  (v/init!))

(deftest public-operation-surface
  (testing "the public namespace exposes the minimal low-level API"
    (let [state runtime-state
          ops   (set (v/operations))
          flip  (v/operation-info "flip")]
      (is (identical? state (v/init!)))
      (is (= "8.17.3" (:version-string state)))
      (is (contains? ops "rotate"))
      (is (contains? ops "arrayjoin"))
      (is (= :horizontal
             (v/decode-enum "VipsDirection"
                            (v/encode-enum "VipsDirection" :horizontal))))
      (is (= "flip" (:name flip))))))

(deftest initialization-blocks-untrusted-operations-by-default
  (testing "runtime initialization blocks libvips operations marked as untrusted"
    (let [calls (atom [])]
      (with-redefs [api/bind-symbols* (fn [_]
                                        {:vips-init                (fn [_] 0)
                                         :vips-version-string      (fn [] "8.17.3")
                                         :vips-block-untrusted-set (fn [state]
                                                                     (swap! calls conj state))})
                    api/build-gtypes  (fn [_] {:boolean :gboolean})]
        (let [state (api/initialize-native-state {:resolve-symbol     identity
                                                  :native-load-source :test})]
          (is (= 1 (count @calls)))
          (is (not (zero? (first @calls))))
          (is (true? (:block-untrusted-operations? state))))))))

(deftest allow-untrusted-operations-override
  (testing "the runtime exposes an explicit override for trusted environments"
    (let [calls   (atom [])
          current {:bindings                    {:vips-block-untrusted-set
                                                 (fn [state]
                                                   (swap! calls conj state))}
                   :block-untrusted-operations? true}]
      (with-redefs [api/ensure-initialized! (fn [] current)]
        (let [state (v/allow-untrusted-operations!)]
          (is (= [0] @calls))
          (is (false? (:block-untrusted-operations? state))))))))

(deftest operation-cache-controls
  (testing "the public runtime exposes cache limits and a disable helper"
    (let [max*       (atom 100)
          max-mem*   (atom 104857600)
          max-files* (atom 100)
          current    {:bindings {:vips-cache-set-max       (fn [max]
                                                             (reset! max* max))
                                 :vips-cache-set-max-mem   (fn [max-mem]
                                                             (reset! max-mem* max-mem))
                                 :vips-cache-set-max-files (fn [max-files]
                                                             (reset! max-files* max-files))
                                 :vips-cache-get-max       (fn [] @max*)
                                 :vips-cache-get-size      (fn [] 3)
                                 :vips-cache-get-max-mem   (fn [] @max-mem*)
                                 :vips-cache-get-max-files (fn [] @max-files*)}}]
      (with-redefs [api/ensure-initialized! (fn [] current)]
        (is (= {:max 100 :size 3 :max-mem 104857600 :max-files 100}
               (v/operation-cache-settings)))
        (is (= {:max 0 :size 3 :max-mem 104857600 :max-files 100}
               (v/disable-operation-cache!)))
        (is (= {:max 12 :size 3 :max-mem 104857600 :max-files 100}
               (v/set-operation-cache-max! 12)))
        (is (= {:max 12 :size 3 :max-mem 4096 :max-files 100}
               (v/set-operation-cache-max-mem! 4096)))
        (is (= {:max 12 :size 3 :max-mem 4096 :max-files 8}
               (v/set-operation-cache-max-files! 8)))))))

(deftest tracked-resource-stats
  (testing "the public runtime exposes libvips tracked resource counters"
    (let [current {:bindings {:vips-tracked-get-mem           (fn [] 2048)
                              :vips-tracked-get-mem-highwater (fn [] 8192)
                              :vips-tracked-get-allocs        (fn [] 7)
                              :vips-tracked-get-files         (fn [] 2)}}]
      (with-redefs [api/ensure-initialized! (fn [] current)]
        (is (= {:mem           2048
                :mem-highwater 8192
                :allocs        7
                :files         2}
               (v/tracked-resources)))))))

(deftest open-and-metadata
  (testing "from-file and metadata accept path strings and Path values"
    (with-open [from-string (v/from-file fixture-path)
                from-path   (v/from-file (java.nio.file.Path/of fixture-path
                                                                (make-array String 0)))]
      (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
             (select-keys (v/metadata from-string) [:width :height :bands :has-alpha?])))
      (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
             (select-keys (v/metadata from-path) [:width :height :bands :has-alpha?])))
      (is (= 2490 (v/width from-string)))
      (is (= 3084 (v/height from-string)))
      (is (= 3 (v/bands from-string)))
      (is (false? (v/has-alpha? from-string)))
      (is (= [2490 3084 3] (v/shape from-string)))
      (is (= (v/metadata from-string)
             (v/metadata from-path))))))

(deftest file-and-buffer-io-helpers
  (testing "write-to-file writes an image to a format inferred from the sink path"
    (let [temp-path (java.nio.file.Files/createTempFile "ol-vips-" ".png"
                                                        (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (with-open [image   (v/from-file fixture-path)
                    written (do
                              (v/write-to-file image temp-path)
                              (v/from-file temp-path))]
          (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
                 (select-keys (v/metadata written) [:width :height :bands :has-alpha?]))))
        (finally
          (java.nio.file.Files/deleteIfExists temp-path)))))
  (testing "from-buffer and write-to-buffer round-trip formatted images"
    (let [fixture-bytes (java.nio.file.Files/readAllBytes
                         (java.nio.file.Path/of fixture-path (make-array String 0)))]
      (with-open [from-buffer (v/from-buffer fixture-bytes)]
        (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
               (select-keys (v/metadata from-buffer) [:width :height :bands :has-alpha?]))))
      (with-open [image     (v/from-file fixture-path)
                  roundtrip (v/from-buffer (v/write-to-buffer image ".png"))]
        (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
               (select-keys (v/metadata roundtrip) [:width :height :bands :has-alpha?])))))))

(deftest stream-io-helpers
  (testing "from-stream reads an image from an InputStream and closes it with the image"
    (let [fixture-bytes (java.nio.file.Files/readAllBytes
                         (java.nio.file.Path/of fixture-path (make-array String 0)))
          closed?       (atom false)
          stream        (proxy [ByteArrayInputStream] [fixture-bytes]
                          (close []
                            (reset! closed? true)
                            (proxy-super close)))]
      (is (false? @closed?))
      (with-open [image (v/from-stream stream)]
        (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
               (select-keys (v/metadata image) [:width :height :bands :has-alpha?])))
        (is (false? @closed?)))
      (is (true? @closed?))))
  (testing "write-to-stream writes through the target callback path and closes the stream"
    (let [flushed? (atom false)
          closed?  (atom false)
          out      (proxy [ByteArrayOutputStream] []
                     (flush []
                       (reset! flushed? true)
                       (proxy-super flush))
                     (close []
                       (reset! closed? true)
                       (proxy-super close)))]
      (with-open [image (v/from-file fixture-path)]
        (v/write-to-stream image out ".png"))
      (is (true? @flushed?))
      (is (true? @closed?))
      (with-open [roundtrip (v/from-buffer (.toByteArray ^ByteArrayOutputStream out))]
        (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
               (select-keys (v/metadata roundtrip) [:width :height :bands :has-alpha?])))))))

(deftest stream-callback-errors
  (testing "from-stream translates callback read failures into ex-info and closes the stream"
    (let [closed? (atom false)
          stream  (proxy [InputStream] []
                    (read
                      ([] (throw (IOException. "read exploded")))
                      ([^bytes _bytes ^Integer _off ^Integer _len]
                       (throw (IOException. "read exploded"))))
                    (close []
                      (reset! closed? true)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"stream"
                            (v/from-stream stream)))
      (is (true? @closed?))))
  (testing "write-to-stream translates callback write failures into ex-info and closes the stream"
    (let [closed? (atom false)
          out     (proxy [OutputStream] []
                    (write
                      ([^Integer _b]
                       (throw (IOException. "write exploded")))
                      ([^bytes _bytes ^Integer _off ^Integer _len]
                       (throw (IOException. "write exploded"))))
                    (close []
                      (reset! closed? true)))]
      (with-open [image (v/from-file fixture-path)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"stream"
                              (v/write-to-stream image out ".png"))))
      (is (true? @closed?)))))

(deftest boxed-double-array-operation-args
  (testing "operations accept boxed VipsArrayDouble inputs such as embed background colors"
    (with-open [image    (v/from-file alpha-band-path)
                bordered (v/call "embed" {:in         image
                                          :x          20
                                          :y          20
                                          :width      (+ (v/width image) 40)
                                          :height     (+ (v/height image) 40)
                                          :extend     :background
                                          :background [231 98 39 255]})
                sample   (v/call "extract_area" {:input  bordered
                                                 :left   0
                                                 :top    0
                                                 :width  1
                                                 :height 1})]
      (is (= (+ (v/width image) 40) (v/width bordered)))
      (is (= (+ (v/height image) 40) (v/height bordered)))
      (is (> (:out (v/call "avg" {:in sample})) 100.0)))))

(deftest load-save-options
  (testing "from-file supports option maps and suffix options"
    (with-open [img1 (v/from-file puppies-path)
                img2 (v/from-file puppies-path {:shrink 2})
                img3 (v/from-file (str puppies-path "[shrink=2]"))
                img4 (v/image-from-file puppies-path)]
      (is (= 518 (v/width img1)))
      (is (= 389 (v/height img1)))
      (is (= 3 (v/bands img1)))
      (is (= (v/metadata img1) (v/metadata img4)))
      (is (= (v/width img1) (* 2 (v/width img2))))
      (is (= (v/width img2) (v/width img3)))
      (is (= (v/height img2) (v/height img3)))))
  (testing "from-buffer supports option maps and rejects invalid input"
    (let [source-bytes (java.nio.file.Files/readAllBytes
                        (java.nio.file.Path/of puppies-path (make-array String 0)))]
      (with-open [img1 (v/from-file puppies-path)
                  img2 (v/from-buffer source-bytes {:shrink 2})]
        (is (= (v/width img1) (* 2 (v/width img2)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Failed to open image from buffer"
                            (v/from-buffer (byte-array 0))))))
  (testing "write-to-file supports option maps and suffix options"
    (let [path1 (java.nio.file.Files/createTempFile "ol-vips-" ".png"
                                                    (make-array java.nio.file.attribute.FileAttribute 0))
          path2 (java.nio.file.Files/createTempFile "ol-vips-" ".png"
                                                    (make-array java.nio.file.attribute.FileAttribute 0))
          path3 (java.nio.file.Files/createTempFile "ol-vips-" ".png"
                                                    (make-array java.nio.file.attribute.FileAttribute 0))
          path4 (java.nio.file.Files/createTempFile "ol-vips-" ".png"
                                                    (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (with-open [img (v/from-file puppies-path)]
          (v/write-to-file img path1 {:compression 0})
          (v/write-to-file img path2 {:compression 9})
          (v/write-to-file img (str path3 "[compression=0]"))
          (v/write-to-file img (str path4 "[compression=9]")))
        (is (> (.size (java.nio.file.Files/readAttributes path1
                                                          java.nio.file.attribute.BasicFileAttributes
                                                          (make-array java.nio.file.LinkOption 0)))
               (.size (java.nio.file.Files/readAttributes path2
                                                          java.nio.file.attribute.BasicFileAttributes
                                                          (make-array java.nio.file.LinkOption 0)))))
        (is (> (.size (java.nio.file.Files/readAttributes path3
                                                          java.nio.file.attribute.BasicFileAttributes
                                                          (make-array java.nio.file.LinkOption 0)))
               (.size (java.nio.file.Files/readAttributes path4
                                                          java.nio.file.attribute.BasicFileAttributes
                                                          (make-array java.nio.file.LinkOption 0)))))
        (finally
          (doseq [path [path1 path2 path3 path4]]
            (java.nio.file.Files/deleteIfExists path))))))
  (testing "write-to-buffer supports option maps and suffix options"
    (with-open [img (v/from-file puppies-path)]
      (let [bin1 (v/write-to-buffer img ".png" {:compression 0})
            bin2 (v/write-to-buffer img ".png" {:compression 9})
            bin3 (v/write-to-buffer img ".png[compression=0]")
            bin4 (v/write-to-buffer img ".png[compression=9]")]
        (is (> (alength ^bytes bin1) (alength ^bytes bin2)))
        (is (> (alength ^bytes bin3) (alength ^bytes bin4)))))))

(deftest alpha-and-shape-helpers
  (testing "alpha and shape match the public image helpers"
    (with-open [jpg (v/from-file puppies-path)
                png (v/from-file alpha-band-path)]
      (is (false? (v/has-alpha? jpg)))
      (is (true? (v/has-alpha? png)))
      (is (= [518 389 3] (v/shape jpg)))
      (is (= 4 (v/bands png))))))

(deftest thumbnail-helper
  (testing "thumbnail returns a derived image handle"
    (with-open [image     (v/from-file fixture-path)
                thumbnail (v/thumbnail image 400 {:auto-rotate true})]
      (is (= {:width 323 :height 400 :bands 3 :has-alpha? false}
             (select-keys (v/metadata thumbnail) [:width :height :bands :has-alpha?]))))))

(deftest image-result-maps-work-as-image-inputs
  (testing "public image helpers accept operation result maps with :out"
    (with-open [image (v/from-file fixture-path)]
      (with-open [autorot (v/call "autorot" {:in image})]
        (let [thumb (v/thumbnail autorot 400)
              png   (v/write-to-buffer autorot ".png")]
          (with-open [thumb thumb]
            (is (= {:width 323 :height 400 :bands 3 :has-alpha? false}
                   (select-keys (v/metadata thumb) [:width :height :bands :has-alpha?])))
            (is (pos? (alength ^bytes png))))))))
  (testing "raw operation calls accept operation result maps for image inputs"
    (with-open [image (v/from-file fixture-path)]
      (with-open [autorot (v/call "autorot" {:in image})
                  flipped (v/call "flip" {:in autorot :direction :horizontal})]
        (is (= {:width 2490 :height 3084 :has-alpha? false}
               (select-keys (v/metadata flipped) [:width :height :has-alpha?])))))))

(deftest single-image-operations-return-image-handles
  (testing "single-image operation results can be used directly in with-open"
    (with-open [image   (v/from-file fixture-path)
                rotated (v/call "rotate" {:in image :angle 90.0})
                flipped (v/call "flip" {:in rotated :direction :horizontal})
                bw      (v/call "colourspace" {:in flipped :space :b-w})]
      (is (= {:width 3084 :height 2490 :bands 1 :has-alpha? false}
             (select-keys (v/metadata bw) [:width :height :bands :has-alpha?]))))))

(deftest transforms
  (testing "rotate, colourspace, and flip compose through call"
    (with-open [image   (v/image-from-file fixture-path)
                rotated (v/call "rotate" {:in image :angle 90.0})
                bw      (v/call "colourspace" {:in image :space :b-w})
                flipped (v/call "flip" {:in image :direction :horizontal})]
      (is (= {:width 3084 :height 2490 :has-alpha? false}
             (select-keys (v/metadata rotated) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/metadata bw) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/metadata flipped) [:width :height :has-alpha?])))))
  (testing "invalid enum keywords fail before entering libvips"
    (with-open [image (v/image-from-file fixture-path)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown enum value"
                            (v/call "flip" {:in image :direction :sideways})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown enum value"
                            (v/call "colourspace" {:in image :space :sepia}))))))

(deftest join-and-array-join
  (testing "join composes two images through the raw operation contract"
    (with-open [left   (v/image-from-file fixture-path)
                right  (v/image-from-file fixture-path)
                joined (v/call "join" {:in1       left
                                       :in2       right
                                       :direction :horizontal})]
      (is (= {:width 4980 :height 3084 :has-alpha? false}
             (select-keys (v/metadata joined) [:width :height :has-alpha?])))))
  (testing "arrayjoin accepts a collection of images plus layout options"
    (with-open [a    (v/image-from-file fixture-path)
                b    (v/image-from-file fixture-path)
                c    (v/image-from-file fixture-path)
                d    (v/image-from-file fixture-path)
                grid (v/call "arrayjoin" {:in     [a b c d]
                                          :across 2
                                          :shim   10
                                          :halign :centre
                                          :valign :centre})]
      (is (= {:width 4990 :height 6178 :has-alpha? false}
             (select-keys (v/metadata grid) [:width :height :has-alpha?]))))))

(deftest metadata-api
  (testing "generic field helpers expose typed metadata values and discovery"
    (with-open [img (ops/gifload animated-gif-path {:n -1})]
      (is (= 5 (v/field img "n-pages")))
      (is (= 77 (v/field img "page-height")))
      (is (= [0 50 50 50 50] (v/field img "delay")))
      (is (= 32761 (v/field img "loop")))
      (is (some #{"delay"} (v/field-names img)))
      (is (= "0 50 50 50 50" (str/trim (v/field-as-string img "delay"))))
      (is (nil? (v/field img "does-not-exist")))
      (is (= ::missing (v/field img "does-not-exist" ::missing)))
      (is (= 5 (get (v/headers img) "n-pages")))))
  (testing "assoc/update/dissoc field operate immutably on image metadata"
    (with-open [img         (v/from-file puppies-path)
                int-added   (v/assoc-field img "custom-int" 42)
                dbl-added   (v/assoc-field int-added "custom-double" 3.5)
                str-added   (v/assoc-field dbl-added "custom-string" "hello")
                arr-added   (v/assoc-field str-added "custom-delay" [10 20 30] {:type :array-int})
                incremented (v/update-field arr-added "custom-int" inc)
                stripped    (v/dissoc-field incremented "custom-string")]
      (is (nil? (v/field img "custom-int")))
      (is (= 42 (v/field int-added "custom-int")))
      (is (= 3.5 (v/field dbl-added "custom-double")))
      (is (= "hello" (v/field str-added "custom-string")))
      (is (= [10 20 30] (v/field arr-added "custom-delay")))
      (is (= 43 (v/field incremented "custom-int")))
      (is (nil? (v/field stripped "custom-string")))
      (is (= 42 (v/field arr-added "custom-int")))))
  (testing "assoc-field persists core header fields like xres and yres through save and reload"
    (let [tmp-path (java.nio.file.Files/createTempFile "ol-vips-meta-" ".jpg"
                                                       (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (with-open [img    (v/from-file puppies-path)
                    tagged (-> img
                               (v/assoc-field "xres" 10.0)
                               (v/assoc-field "yres" 10.0))]
          (is (= 10.0 (v/field tagged "xres")))
          (is (= 10.0 (v/field tagged "yres")))
          (v/write-to-file tagged tmp-path {:strip false}))
        (with-open [roundtrip (v/from-file tmp-path)]
          (is (= 10.0 (v/field roundtrip "xres")))
          (is (= 10.0 (v/field roundtrip "yres"))))
        (finally
          (java.nio.file.Files/deleteIfExists tmp-path))))))

(deftest animated-metadata-and-readers
  (testing "metadata includes animated keys and dedicated readers mirror generic fields"
    (with-open [img (ops/gifload animated-gif-path {:n -1})]
      (is (= {:width       85
              :height      385
              :bands       4
              :has-alpha?  true
              :pages       5
              :page-height 77
              :loop        32761
              :delay       [0 50 50 50 50]}
             (select-keys (v/metadata img)
                          [:width :height :bands :has-alpha? :pages :page-height :loop :delay])))
      (is (= 5 (v/pages img)))
      (is (= 77 (v/page-height img)))
      (is (= [0 50 50 50 50] (v/page-delays img)))
      (is (= 32761 (v/loop-count img))))))

(deftest animated-metadata-writers
  (testing "assoc-page helpers return new images and preserve the original"
    (with-open [img     (ops/gifload animated-gif-path {:n -1})
                pages*  (v/assoc-pages img 9)
                height* (v/assoc-page-height pages* 11)
                delay*  (v/assoc-page-delays height* [1 2 3 4 5 6 7 8 9])
                loop*   (v/assoc-loop-count delay* 7)]
      (is (= 5 (v/pages img)))
      (is (= 77 (v/page-height img)))
      (is (= [0 50 50 50 50] (v/page-delays img)))
      (is (= 32761 (v/loop-count img)))
      (is (= 9 (v/pages pages*)))
      (is (= 11 (v/page-height height*)))
      (is (= [1 2 3 4 5 6 7 8 9] (v/page-delays delay*)))
      (is (= 7 (v/loop-count loop*)))))
  (testing "assoc-page-delays validates delay counts when page count is known"
    (with-open [img (ops/gifload animated-gif-path {:n -1})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"delay"
                            (v/assoc-page-delays img [1 2]))))))

(deftest animated-frame-aware-transforms
  (testing "extract-area-pages crops each frame and preserves page metadata"
    (with-open [img     (ops/gifload animated-gif-path {:n -1})
                cropped (v/extract-area-pages img 10 7 20 11)]
      (is (= {:width       20
              :height      55
              :pages       5
              :page-height 11
              :loop        32761
              :delay       [0 50 50 50 50]}
             (select-keys (v/metadata cropped) [:width :height :pages :page-height :loop :delay])))))
  (testing "embed-pages applies to each frame and updates page height"
    (with-open [img      (ops/gifload animated-gif-path {:n -1})
                embedded (v/embed-pages img 3 4 100 90 {:extend     :background
                                                        :background [0 0 0 0]})]
      (is (= {:width 100 :height 450 :pages 5 :page-height 90}
             (select-keys (v/metadata embedded) [:width :height :pages :page-height])))))
  (testing "rot-pages rotates each frame and updates frame geometry"
    (with-open [img    (ops/gifload animated-gif-path {:n -1})
                turned (v/rot-pages img :d90)]
      (is (= {:width 77 :height 425 :pages 5 :page-height 85}
             (select-keys (v/metadata turned) [:width :height :pages :page-height])))))
  (testing "single-page inputs behave like the ordinary operations"
    (with-open [img     (v/from-file puppies-path)
                cropped (v/extract-area-pages img 0 0 20 30)
                turned  (v/rot-pages img :d90)]
      (is (= {:width 20 :height 30}
             (select-keys (v/metadata cropped) [:width :height])))
      (is (= {:width 389 :height 518}
             (select-keys (v/metadata turned) [:width :height])))
      (is (nil? (v/pages cropped)))
      (is (nil? (v/page-height turned))))))

(deftest animated-assembly
  (testing "assemble-pages joins equal-sized frames and round-trips through gif save"
    (let [tmp-path (java.nio.file.Files/createTempFile "ol-vips-animated-" ".gif"
                                                       (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (with-open [base     (v/from-file puppies-path)
                    frame-a  (ops/extract-area base 0 0 40 30)
                    frame-b  (ops/extract-area base 10 10 40 30)
                    frame-c  (ops/extract-area base 20 20 40 30)
                    animated (v/assemble-pages [frame-a frame-b frame-c]
                                               {:loop  2
                                                :delay [80 120 160]})]
          (is (= {:width       40
                  :height      90
                  :pages       3
                  :page-height 30
                  :loop        2
                  :delay       [80 120 160]}
                 (select-keys (v/metadata animated)
                              [:width :height :pages :page-height :loop :delay])))
          (v/write-to-file animated tmp-path)
          (with-open [roundtrip (ops/gifload tmp-path {:n -1})]
            (is (= {:width       40
                    :height      90
                    :pages       3
                    :page-height 30
                    :loop        2
                    :delay       [80 120 160]}
                   (select-keys (v/metadata roundtrip)
                                [:width :height :pages :page-height :loop :delay])))))
        (finally
          (java.nio.file.Files/deleteIfExists tmp-path)))))
  (testing "assemble-pages rejects mixed frame sizes"
    (with-open [base    (v/from-file puppies-path)
                frame-a (ops/extract-area base 0 0 40 30)
                frame-b (ops/extract-area base 0 0 20 30)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"same size"
                            (v/assemble-pages [frame-a frame-b] {:delay [10 10]})))))
  (testing "assemble-pages rejects mismatched delay counts"
    (with-open [base    (v/from-file puppies-path)
                frame-a (ops/extract-area base 0 0 40 30)
                frame-b (ops/extract-area base 10 10 40 30)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"delay"
                            (v/assemble-pages [frame-a frame-b] {:delay [10]}))))))
