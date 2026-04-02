(ns ol.vips-native-packaging-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [ol.vips.native.loader :as loader]
   [ol.vips.native.platforms :as platforms])
  (:import
   [clojure.lang ExceptionInfo]
   [java.nio.file Files]))

(defmacro with-system-properties
  [bindings & body]
  (let [pairs (partition 2 bindings)
        saved (gensym "saved")]
    `(let [~saved (zipmap ~(vec (map first pairs))
                          (map #(System/getProperty %) ~(vec (map first pairs))))]
       (try
         ~@(map (fn [[property value]]
                  `(if (nil? ~value)
                     (System/clearProperty ~property)
                     (System/setProperty ~property ~value)))
                pairs)
         ~@body
         (finally
           ~@(map (fn [[property _]]
                    `(if-some [value# (get ~saved ~property)]
                       (System/setProperty ~property value#)
                       (System/clearProperty ~property)))
                  pairs))))))

(deftest supported-platforms-remain-explicit
  (testing "the supported platform ids stay aligned with the platform table"
    (is (= platforms/supported-platform-ids
           (mapv :platform-id platforms/supported-platforms))))
  (testing "platform lookup returns the matching descriptor"
    (is (= {:platform-id :linux-x86-64-gnu
            :os          :linux
            :arch        :x86-64
            :libc        :glibc}
           (select-keys (platforms/platform :linux-x86-64-gnu)
                        [:platform-id :os :arch :libc]))))
  (testing "unsupported platform ids fail with the supported set in ex-data"
    (let [error (try
                  (platforms/platform :plan9-x86-64)
                  (catch ExceptionInfo ex
                    ex))]
      (is (instance? ExceptionInfo error))
      (is (= :plan9-x86-64 (:platform-id (ex-data error))))
      (is (= platforms/supported-platform-ids
             (:supported (ex-data error)))))))

(deftest os-and-arch-normalization
  (testing "os names normalize to supported keywords"
    (is (= :linux (loader/normalize-os "Linux")))
    (is (= :linux (loader/normalize-os "GNU/Linux")))
    (is (= :macos (loader/normalize-os "Mac OS X")))
    (is (= :macos (loader/normalize-os "Darwin")))
    (is (= :windows (loader/normalize-os "Windows 11"))))
  (testing "arch names normalize common aliases"
    (is (= :x86-64 (loader/normalize-arch "amd64")))
    (is (= :x86-64 (loader/normalize-arch "x86_64")))
    (is (= :aarch64 (loader/normalize-arch "aarch64")))
    (is (= :aarch64 (loader/normalize-arch "arm64"))))
  (testing "unsupported os and arch values throw"
    (is (thrown? ExceptionInfo (loader/normalize-os "Solaris")))
    (is (thrown? ExceptionInfo (loader/normalize-arch "sparc")))))

(deftest detect-host-platform-from-properties
  (testing "explicit os, arch, and libc properties resolve the expected platform"
    (with-system-properties ["ol.vips.native.platform-id" nil
                             "ol.vips.native.os" "Linux"
                             "ol.vips.native.arch" "amd64"
                             "ol.vips.native.libc" "glibc"]
      (is (= :linux-x86-64-gnu
             (:platform-id (loader/detect-host-platform))))))
  (testing "platform-id override wins over the other properties"
    (with-system-properties ["ol.vips.native.platform-id" "win32-x86-64"
                             "ol.vips.native.os" "Linux"
                             "ol.vips.native.arch" "amd64"
                             "ol.vips.native.libc" "glibc"]
      (is (= :win32-x86-64
             (:platform-id (loader/detect-host-platform))))))
  (testing "non-linux platforms do not need libc"
    (with-system-properties ["ol.vips.native.platform-id" nil
                             "ol.vips.native.os" "Mac OS X"
                             "ol.vips.native.arch" "arm64"
                             "ol.vips.native.libc" nil]
      (is (= {:platform-id :macos-aarch64
              :os          :macos
              :arch        :aarch64
              :libc        nil}
             (select-keys (loader/detect-host-platform)
                          [:platform-id :os :arch :libc]))))))

(deftest manifest-and-cache-path-derivation
  (let [manifest {:platform-id   :linux-x86-64-gnu
                  :vips-version  "8.17.3"
                  :sharp-version "1.2.4"
                  :library-files ["lib/libvips-cpp.so.8.17.3"]}]
    (testing "manifest resource paths follow the classpath contract"
      (is (= "ol/vips/native/linux-x86-64-gnu/manifest.edn"
             (loader/manifest-resource-path :linux-x86-64-gnu))))
    (testing "cache roots honor the explicit override"
      (with-system-properties ["ol.vips.native.cache-root" "/tmp/ol-vips-cache"]
        (is (= "/tmp/ol-vips-cache"
               (str (loader/default-cache-root))))))
    (testing "extraction roots are deterministic"
      (is (= "/tmp/ol.vips/linux-x86-64-gnu/8.17.3-1.2.4"
             (str (loader/extraction-root "/tmp/ol.vips" manifest)))))
    (testing "extracted library paths derive from the manifest order"
      (is (= ["/tmp/ol.vips/linux-x86-64-gnu/8.17.3-1.2.4/lib/libvips-cpp.so.8.17.3"]
             (loader/extracted-library-paths "/tmp/ol.vips" manifest))))))

(deftest sync-native-versions-composes-companion-version-from-sharp-release-and-revision
  (let [temp-native-root (str (Files/createTempDirectory
                               "ol-vips-native-test-"
                               (make-array java.nio.file.attribute.FileAttribute 0)))
        platform         (platforms/platform :linux-x86-64-gnu)
        deps-path        (io/file temp-native-root (:dir-name platform) "deps.edn")]
    (try
      (let [{:keys [exit err]} (shell/sh "clojure"
                                         "-T:build"
                                         "sync-native-versions"
                                         ":sharp-vips-version"
                                         "\"1.2.3\""
                                         ":native-version-revision"
                                         "4"
                                         ":native-root"
                                         (pr-str temp-native-root))]
        (is (zero? exit) err)
        (let [project (get-in (edn/read-string (slurp deps-path))
                              [:aliases :neil :project])]
          (is (= "1.2.3-4" (:version project)))
          (is (= "LGPL-3.0-or-later" (get-in project [:license :id])))
          (is (= "THIRD-PARTY-NOTICES.md" (:notice-file project)))
          (is (= "Platform-native bundle for com.outskirtslabs/vips (linux-x86-64-gnu)"
                 (:description project)))))
      (finally
        (when (.exists (io/file temp-native-root))
          (doseq [file (reverse (file-seq (io/file temp-native-root)))]
            (.delete ^java.io.File file)))))))

(deftest sync-native-versions-defaults-to-project-sharp-vips-version-and-revision
  (let [temp-native-root (str (Files/createTempDirectory
                               "ol-vips-native-test-"
                               (make-array java.nio.file.attribute.FileAttribute 0)))
        platform         (platforms/platform :linux-x86-64-gnu)
        deps-path        (io/file temp-native-root (:dir-name platform) "deps.edn")]
    (try
      (let [{:keys [exit err]} (shell/sh "clojure"
                                         "-T:build"
                                         "sync-native-versions"
                                         ":native-root"
                                         (pr-str temp-native-root))]
        (is (zero? exit) err)
        (let [project (get-in (edn/read-string (slurp deps-path))
                              [:aliases :neil :project])]
          (is (= "1.2.4-0" (:version project)))
          (is (= "LGPL-3.0-or-later" (get-in project [:license :id])))))
      (finally
        (when (.exists (io/file temp-native-root))
          (doseq [file (reverse (file-seq (io/file temp-native-root)))]
            (.delete ^java.io.File file)))))))
