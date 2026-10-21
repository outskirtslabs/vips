(ns ol.vips-io-test
  (:require [babashka.ffi :as ffi]
            [clojure.test :refer [deftest is]]
            [ol.vips :as v]
            [ol.vips.impl.api :as api]
            [ol.vips.impl.introspect :as introspect]
            [ol.vips.impl.loader :as loader]
            [ol.vips.impl.io :as image-io])
  (:import [java.io ByteArrayInputStream File InputStream IOException]
           [java.nio.file Files]))

(defn fixture-bytes []
  (Files/readAllBytes (.toPath (File. "test/fixtures/puppies.jpg"))))

(deftest native-bindings-are-fixed
  (is (not-any? #(some #{:&} (second %)) (vals @#'api/native-symbol-specs))))

(deftest native-options-and-buffer-lifetime
  (with-open [image (v/from-file "test/fixtures/puppies.jpg[shrink=1]" {:shrink "2"})]
    (is (= 259 (v/width image))))
  (let [original (v/from-buffer (seq (fixture-bytes)) {:shrink "2"})]
    (with-open [derived (v/call "resize" {:in original :scale 0.5})]
      (.close ^java.lang.AutoCloseable original)
      (.close ^java.lang.AutoCloseable original)
      (with-open [roundtrip (v/from-buffer (v/write-to-buffer derived ".png[compression=1]" {:compression "2"}))]
        (is (= 130 (v/width roundtrip)))))))

(deftest source-lifetime-survives-original-close
  (let [closed   (atom 0)
        input    (ByteArrayInputStream. (fixture-bytes))
        stream   (proxy [InputStream] []
                   (read
                     ([] (.read input))
                     ([buffer] (.read input ^bytes buffer))
                     ([buffer offset length] (.read input ^bytes buffer offset length)))
                   (close [] (swap! closed inc) (.close input)))
        original (v/from-stream stream)]
    (with-open [derived (v/call "resize" {:in original :scale 0.5})]
      (.close ^java.lang.AutoCloseable original)
      (with-open [roundtrip (v/from-buffer (v/write-to-buffer derived ".png"))]
        (is (= 259 (v/width roundtrip)))))
    (let [maximum ((api/bindings :vips-cache-get-max))]
      (try
        ((api/bindings :vips-cache-set-max) 0)
        (is (= 1 @closed))
        (finally ((api/bindings :vips-cache-set-max) maximum))))))

(deftest legacy-saver-fallbacks
  ;; PNG supports targets here. Only discovery is controlled; both legacy
  ;; operations still encode real images through the native operation cache.
  (with-open [image (v/from-file "test/fixtures/puppies.jpg")]
    (with-redefs [image-io/speculative-discover (fn [& _] nil)]
      (with-open [roundtrip (v/from-buffer (v/write-to-buffer image ".png"))]
        (is (= 518 (v/width roundtrip))))
      (let [file (File/createTempFile "vips-io-fallback-" ".png")]
        (try
          (is (identical? image (v/write-to-file image file)))
          (with-open [roundtrip (v/from-file file)]
            (is (= 518 (v/width roundtrip))))
          (finally (.delete file)))))))

(deftest source-fallbacks
  (let [resolve-symbol (:resolve-symbol (loader/load-native!))
        from-memory    (ffi/cfn (resolve-symbol "vips_source_new_from_memory") [:pointer :size_t] :pointer)
        from-file      (ffi/cfn (resolve-symbol "vips_source_new_from_file") [:string] :pointer)
        data           (fixture-bytes)]
    (with-open [arena (ffi/confined-arena)]
      (let [buffer (ffi/alloc arena (alength ^bytes data) 1)]
        (ffi/write-array buffer :byte data)
        (with-open [memory-source (image-io/connection-handle (from-memory buffer (alength ^bytes data)))
                    file-source   (image-io/connection-handle (from-file "test/fixtures/puppies.jpg"))]
          (with-redefs [image-io/speculative-discover (fn [& _] nil)]
            (doseq [source [memory-source file-source]]
              (with-open [image (image-io/open-source source "[shrink=2]" {})]
                (is (= 259 (v/width image)))))))))))

(deftest typed-object-and-blob-conversions
  (with-open [image  (v/from-file "test/fixtures/puppies.jpg")
              target (image-io/connection-handle ((api/bindings :target-new-to-memory)))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (introspect/call-operation "pngsave_target" {:in image :target image})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (introspect/call-operation "resize" {:in target :scale 1.0})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (introspect/call-operation "jpegload_buffer" {:buffer ffi/null})))
    (let [bytes (:buffer (introspect/call-operation "pngsave_buffer" {:in image}))]
      (with-open [roundtrip (v/from-buffer bytes)]
        (is (= 518 (v/width roundtrip)))))))

(deftest delayed-source-errors-retain-cause
  (doseq [evaluate [#(v/write-to-buffer % ".png")
                    (fn [image]
                      (with-open [copy (v/copy-memory image)] (v/width copy)))]]
    (let [input            (ByteArrayInputStream. (fixture-bytes))
          fail?            (atom false)
          failure          (IOException. "delayed input failure")
          caller-thread    (.getId (Thread/currentThread))
          callback-threads (atom #{})
          stream           (proxy [InputStream] []
                             (read
                               ([] (if @fail? (throw failure) (.read input)))
                               ([buffer] (if @fail? (throw failure) (.read input ^bytes buffer)))
                               ([buffer offset length]
                                (swap! callback-threads conj (.getId (Thread/currentThread)))
                                (if @fail? (throw failure) (.read input ^bytes buffer offset length))))
                             (close [] (.close input)))
          original         (v/from-stream stream {:access :sequential :fail-on :warning})]
      (with-open [derived (v/call "resize" {:in original :scale 0.5})]
        (.close ^java.lang.AutoCloseable original)
        (reset! fail? true)
        (let [error (try (evaluate derived) nil (catch Exception e e))]
          (is (some? error))
          (is (seq (:vips/error (ex-data error))))
          (is (identical? failure (ex-cause error)))
          (is (seq (disj @callback-threads caller-thread)))
          (is (= {:stream/error-class "java.io.IOException"
                  :stream/error       "delayed input failure"}
                 (select-keys (ex-data error) [:stream/error-class :stream/error]))))))))

(deftest output-conversion-failure-closes-acquired-images
  (let [decode   @#'introspect/decode-value
        acquired (atom nil)]
    (with-redefs-fn {#'introspect/decode-value
                     (fn [native arg value failure-refs]
                       (if (= :flags (:kind arg))
                         (throw (ex-info "decode failure" {}))
                         (let [result (decode native arg value failure-refs)]
                           (when (= :object (:kind arg)) (reset! acquired result))
                           result)))}
      (fn []
        (is (thrown? clojure.lang.ExceptionInfo
                     (v/call "jpegload" {:filename "test/fixtures/puppies.jpg"})))
        (is (some? @acquired))
        (is (thrown? clojure.lang.ExceptionInfo (v/width @acquired)))))))

(deftest independent-concurrent-streams
  (let [jobs (mapv (fn [_]
                     (future
                       (with-open [image     (v/from-stream (ByteArrayInputStream. (fixture-bytes)))
                                   derived   (v/call "resize" {:in image :scale 0.5})
                                   roundtrip (v/from-buffer (v/write-to-buffer derived ".png"))]
                         (v/width roundtrip))))
                   (range 4))]
    (is (= [259 259 259 259] (mapv deref jobs)))))