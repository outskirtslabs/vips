(ns ol.vips-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [ol.vips :as v]))

(def fixture-path
  (str (fs/path "dev" "rabbit.jpg")))

(def fixture-root
  (fs/path "test" "fixtures"))

(def puppies-path
  (str (fs/path fixture-root "puppies.jpg")))

(def alpha-band-path
  (str (fs/path fixture-root "alpha_band.png")))

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

(deftest open-and-image-info
  (testing "from-file and info accept path strings and Path values"
    (with-open [from-string (v/from-file fixture-path)
                from-path   (v/from-file (java.nio.file.Path/of fixture-path
                                                                (make-array String 0)))]
      (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
             (select-keys (v/info from-string) [:width :height :bands :has-alpha?])))
      (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
             (select-keys (v/info from-path) [:width :height :bands :has-alpha?])))
      (is (= 2490 (v/width from-string)))
      (is (= 3084 (v/height from-string)))
      (is (= 3 (v/bands from-string)))
      (is (false? (v/has-alpha? from-string)))
      (is (= [2490 3084 3] (v/shape from-string)))
      (is (= (v/info from-string)
             (v/image-info from-string))))))

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
                 (select-keys (v/info written) [:width :height :bands :has-alpha?]))))
        (finally
          (java.nio.file.Files/deleteIfExists temp-path)))))
  (testing "from-buffer and write-to-buffer round-trip formatted images"
    (let [fixture-bytes (java.nio.file.Files/readAllBytes
                         (java.nio.file.Path/of fixture-path (make-array String 0)))]
      (with-open [from-buffer (v/from-buffer fixture-bytes)]
        (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
               (select-keys (v/info from-buffer) [:width :height :bands :has-alpha?]))))
      (with-open [image     (v/from-file fixture-path)
                  roundtrip (v/from-buffer (v/write-to-buffer image ".png"))]
        (is (= {:width 2490 :height 3084 :bands 3 :has-alpha? false}
               (select-keys (v/info roundtrip) [:width :height :bands :has-alpha?])))))))

(deftest stream-io-helpers
  (let [fixture-bytes (java.nio.file.Files/readAllBytes
                       (java.nio.file.Path/of fixture-path (make-array String 0)))
        expected-info {:width 2490 :height 3084 :bands 3 :has-alpha? false}]
    (testing "from-stream reads images from an input stream"
      (with-open [source      (java.io.ByteArrayInputStream. fixture-bytes)
                  from-stream (v/from-stream source)]
        (is (= expected-info
               (select-keys (v/info from-stream) [:width :height :bands :has-alpha?])))))
    (testing "from-stream does not rely on InputStream.readAllBytes"
      (let [delegate (java.io.ByteArrayInputStream. fixture-bytes)]
        (with-open [source      (proxy [java.io.InputStream] []
                                  (read
                                    ([] (.read delegate))
                                    ([buffer offset length]
                                     (.read delegate buffer offset length)))
                                  (readAllBytes []
                                    (throw (ex-info "readAllBytes should not be called" {})))
                                  (close []
                                    (.close delegate)))
                    from-stream (v/from-stream source)]
          (is (= expected-info
                 (select-keys (v/info from-stream) [:width :height :bands :has-alpha?]))))))
    (testing "from-stream tolerates an intermediate zero-byte read"
      (let [delegate       (java.io.ByteArrayInputStream. fixture-bytes)
            returned-zero? (java.util.concurrent.atomic.AtomicBoolean. false)]
        (with-open [source      (proxy [java.io.InputStream] []
                                  (read
                                    ([] (.read delegate))
                                    ([buffer offset length]
                                     (if (.compareAndSet returned-zero? false true)
                                       0
                                       (.read delegate buffer offset length))))
                                  (close []
                                    (.close delegate)))
                    from-stream (v/from-stream source)]
          (is (= expected-info
                 (select-keys (v/info from-stream) [:width :height :bands :has-alpha?]))))))
    (testing "from-stream surfaces producer exceptions cleanly"
      (let [delegate    (java.io.ByteArrayInputStream. fixture-bytes)
            read-count  (java.util.concurrent.atomic.AtomicInteger. 0)
            close-count (java.util.concurrent.atomic.AtomicInteger. 0)
            source      (proxy [java.io.InputStream] []
                          (read
                            ([] (.read delegate))
                            ([buffer offset length]
                             (if (zero? (.getAndIncrement read-count))
                               (.read delegate buffer offset (min length 2048))
                               (throw (java.io.IOException. "stream exploded")))))
                          (close []
                            (.incrementAndGet close-count)
                            (.close delegate)))
            thrown      (try
                          (with-open [source source]
                            (v/from-stream source))
                          (catch Throwable t
                            t))]
        (is (instance? Throwable thrown))
        (is (= "stream exploded"
               (some-> thrown ex-cause ex-message)))
        (is (= 1 (.get close-count)))))
    (testing "from-chunks reads images from chunked binary data"
      (let [chunks (partition-all 4096 fixture-bytes)]
        (with-open [from-chunks (v/from-chunks chunks)]
          (is (= expected-info
                 (select-keys (v/info from-chunks) [:width :height :bands :has-alpha?]))))))
    (testing "from-chunks closes a closeable chunk source"
      (let [closed? (atom false)
            chunks  (reify
                      clojure.lang.Seqable
                      (seq [_]
                        (seq (map byte-array (partition-all 4096 fixture-bytes))))
                      java.lang.AutoCloseable
                      (close [_]
                        (reset! closed? true)))]
        (with-open [from-chunks (v/from-chunks chunks)]
          (is (= expected-info
                 (select-keys (v/info from-chunks) [:width :height :bands :has-alpha?]))))
        (is (true? @closed?))))
    (testing "write-to-stream returns chunked binary output that can be read back"
      (with-open [image     (v/from-file fixture-path)
                  roundtrip (v/from-chunks (v/write-to-stream image ".png"))]
        (is (= expected-info
               (select-keys (v/info roundtrip) [:width :height :bands :has-alpha?])))))
    (testing "write-to-stream supports save options and chunk sizing"
      (with-open [image (v/from-file puppies-path)]
        (let [bin1 (->> (v/write-to-stream image ".png" {:compression 0 :chunk-size 2048})
                        (map #(alength ^bytes %))
                        (reduce + 0))
              bin2 (->> (v/write-to-stream image ".png" {:compression 9 :chunk-size 2048})
                        (map #(alength ^bytes %))
                        (reduce + 0))]
          (is (> bin1 bin2)))))
    (testing "write-to-stream can be closed early and multiple close calls are safe"
      (with-open [image (v/from-file fixture-path)]
        (let [result (deref
                      (future
                        (with-open [stream (v/write-to-stream image ".png" {:chunk-size 1024})]
                          (is (bytes? (first (seq stream))))
                          (.close ^java.lang.AutoCloseable stream)
                          (.close ^java.lang.AutoCloseable stream)
                          :closed))
                      5000
                      ::timeout)]
          (is (= :closed result)))))))

(deftest load-save-options
  (testing "from-file supports option maps and suffix options"
    (with-open [img1 (v/from-file puppies-path)
                img2 (v/from-file puppies-path {:shrink 2})
                img3 (v/from-file (str puppies-path "[shrink=2]"))
                img4 (v/image-from-file puppies-path)]
      (is (= 518 (v/width img1)))
      (is (= 389 (v/height img1)))
      (is (= 3 (v/bands img1)))
      (is (= (v/info img1) (v/info img4)))
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
             (select-keys (v/info thumbnail) [:width :height :bands :has-alpha?]))))))

(deftest image-result-maps-work-as-image-inputs
  (testing "public image helpers accept operation result maps with :out"
    (with-open [image (v/from-file fixture-path)]
      (with-open [autorot (v/call! "autorot" {:in image})]
        (let [thumb (v/thumbnail autorot 400)
              png   (v/write-to-buffer autorot ".png")]
          (with-open [thumb thumb]
            (is (= {:width 323 :height 400 :bands 3 :has-alpha? false}
                   (select-keys (v/info thumb) [:width :height :bands :has-alpha?])))
            (is (pos? (alength ^bytes png))))))))
  (testing "raw operation calls accept operation result maps for image inputs"
    (with-open [image (v/from-file fixture-path)]
      (with-open [autorot (v/call! "autorot" {:in image})
                  flipped (v/call! "flip" {:in autorot :direction :horizontal})]
        (is (= {:width 2490 :height 3084 :has-alpha? false}
               (select-keys (v/image-info flipped) [:width :height :has-alpha?])))))))

(deftest single-image-operations-return-image-handles
  (testing "single-image operation results can be used directly in with-open"
    (with-open [image   (v/from-file fixture-path)
                rotated (v/call! "rotate" {:in image :angle 90.0})
                flipped (v/call! "flip" {:in rotated :direction :horizontal})
                bw      (v/call! "colourspace" {:in flipped :space :b-w})]
      (is (= {:width 3084 :height 2490 :bands 1 :has-alpha? false}
             (select-keys (v/info bw) [:width :height :bands :has-alpha?]))))))

(deftest transforms
  (testing "rotate, colourspace, and flip compose through call!"
    (with-open [image   (v/image-from-file fixture-path)
                rotated (v/call! "rotate" {:in image :angle 90.0})
                bw      (v/call! "colourspace" {:in image :space :b-w})
                flipped (v/call! "flip" {:in image :direction :horizontal})]
      (is (= {:width 3084 :height 2490 :has-alpha? false}
             (select-keys (v/image-info rotated) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info bw) [:width :height :has-alpha?])))
      (is (= {:width 2490 :height 3084 :has-alpha? false}
             (select-keys (v/image-info flipped) [:width :height :has-alpha?])))))
  (testing "invalid enum keywords fail before entering libvips"
    (with-open [image (v/image-from-file fixture-path)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown enum value"
                            (v/call! "flip" {:in image :direction :sideways})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown enum value"
                            (v/call! "colourspace" {:in image :space :sepia}))))))

(deftest join-and-array-join
  (testing "join composes two images through the raw operation contract"
    (with-open [left   (v/image-from-file fixture-path)
                right  (v/image-from-file fixture-path)
                joined (v/call! "join" {:in1       left
                                        :in2       right
                                        :direction :horizontal})]
      (is (= {:width 4980 :height 3084 :has-alpha? false}
             (select-keys (v/image-info joined) [:width :height :has-alpha?])))))
  (testing "arrayjoin accepts a collection of images plus layout options"
    (with-open [a    (v/image-from-file fixture-path)
                b    (v/image-from-file fixture-path)
                c    (v/image-from-file fixture-path)
                d    (v/image-from-file fixture-path)
                grid (v/call! "arrayjoin" {:in     [a b c d]
                                           :across 2
                                           :shim   10
                                           :halign :centre
                                           :valign :centre})]
      (is (= {:width 4990 :height 6178 :has-alpha? false}
             (select-keys (v/image-info grid) [:width :height :has-alpha?]))))))
