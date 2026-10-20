(ns ol.vips-runtime-portability-test
  (:require
   [babashka.ffi :as ffi]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ol.vips.impl.handles :as handles]
   [ol.vips.impl.handles.protocols :as handle-protocols]
   [ol.vips.impl.loader :as loader])
  (:import
   [java.lang AutoCloseable]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.util.concurrent.atomic AtomicReference]))

(deftest portable-packaged-extraction-and-scoped-lookup
  (let [root (Files/createTempDirectory "ol-vips-portable-" (make-array FileAttribute 0))]
    (try
      (let [manifest  (loader/read-manifest)
            extracted (loader/extract-libraries! root manifest)
            libraries (mapv ffi/load-library (:library-paths extracted))
            resolve!  #(#'loader/lookup-symbol libraries %)
            version   (ffi/cfn (resolve! "vips_version") [:int] :int)]
        (is (= (:vips-version manifest)
               (str/join "." (map version [0 1 2]))))
        (is (= extracted (loader/extract-libraries! root manifest)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Native symbol not found"
                              (resolve! "ol_vips_deliberately_missing_symbol")))
        (testing "lookup does not fall through to a globally loaded libvips"
          (when (= :linux-x86-64-gnu (:platform-id manifest))
            (let [libc (ffi/load-library "libc.so.6")]
              (is (some? (ffi/find-symbol "vips_init")))
              (is (some? (#'loader/lookup-symbol [libc] "malloc")))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                    #"Native symbol not found"
                                    (#'loader/lookup-symbol [libc] "vips_init")))))))
      (finally
        (doseq [file (reverse (file-seq (io/file (str root))))]
          (io/delete-file file))))))

(deftest portable-handle-and-result-contract
  (with-open [arena (ffi/confined-arena)]
    (let [ptr      (ffi/alloc arena :int)
          released (atom [])
          image    (handles/->ImageHandle ptr nil
                                          #(swap! released conj %))
          values   {:out image :width 42}
          result   (handles/->OperationResult values)]
      (with-open [^AutoCloseable result result]
        (is (= ptr (handle-protocols/pointer (:out result))))
        (is (= [true image :missing true [:width 42] 2 values
                (assoc values :height 7) {:width 42} {} true]
               [(map? result) (:out result) (get result :missing :missing)
                (contains? result :width) (find result :width) (count result)
                (into {} result) (assoc result :height 7) (dissoc result :out)
                (empty result) (= result values)])))
      (is (= (conj values [:height 7]) (conj result [:height 7])))
      (.close ^AutoCloseable result)
      (.close ^AutoCloseable image)
      (is (= [ptr] @released))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed image handle"
                            (handle-protocols/pointer image))))))

(deftest portable-stream-failure-access
  (with-open [arena (ffi/confined-arena)]
    (let [ptr      (ffi/alloc arena :int)
          failure  (AtomicReference. (ex-info "stream failure" {}))
          released (atom [])
          bridge   (handles/->StreamBridge ptr failure #(swap! released conj %))]
      (is (identical? failure (handle-protocols/stream-failure-ref bridge)))
      (is (= ptr (handle-protocols/pointer bridge)))
      (.close ^AutoCloseable bridge)
      (.close ^AutoCloseable bridge)
      (is (= [ptr] @released))
      (is (identical? failure (handle-protocols/stream-failure-ref bridge))))))

(deftest concurrent-close-releases-once
  (with-open [arena (ffi/confined-arena)]
    (let [ptr      (ffi/alloc arena :int)
          released (atom [])
          image    (handles/->ImageHandle ptr nil #(swap! released conj %))
          start    (promise)
          workers  (mapv (fn [_] (future @start (.close ^AutoCloseable image))) (range 8))]
      (deliver start true)
      (doseq [worker workers] @worker)
      (is (= [ptr] @released))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed image handle"
                            (handle-protocols/pointer image))))))
