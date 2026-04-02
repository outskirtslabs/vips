(ns ol.vips-native-packaging-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.util.jar JarFile)))

(def root-project
  (-> (slurp "deps.edn")
      edn/read-string
      :aliases
      :neil
      :project))

(def root-version
  (:version root-project))

(def pinned-vips-version
  (:vips-version root-project))

(def expected-platforms
  [{:platform-id   :linux-x86-64-gnu
    :dir           "native/linux-x86-64-gnu"
    :artifact-name "com.outskirtslabs/vips-native-linux-x86-64-gnu"
    :sharp-package "@img/sharp-libvips-linux-x64"
    :os            :linux
    :arch          :x86-64
    :libc          :glibc}
   {:platform-id   :linux-x86-64-musl
    :dir           "native/linux-x86-64-musl"
    :artifact-name "com.outskirtslabs/vips-native-linux-x86-64-musl"
    :sharp-package "@img/sharp-libvips-linuxmusl-x64"
    :os            :linux
    :arch          :x86-64
    :libc          :musl}
   {:platform-id   :linux-aarch64-gnu
    :dir           "native/linux-aarch64-gnu"
    :artifact-name "com.outskirtslabs/vips-native-linux-aarch64-gnu"
    :sharp-package "@img/sharp-libvips-linux-arm64"
    :os            :linux
    :arch          :aarch64
    :libc          :glibc}
   {:platform-id   :linux-aarch64-musl
    :dir           "native/linux-aarch64-musl"
    :artifact-name "com.outskirtslabs/vips-native-linux-aarch64-musl"
    :sharp-package "@img/sharp-libvips-linuxmusl-arm64"
    :os            :linux
    :arch          :aarch64
    :libc          :musl}
   {:platform-id   :macos-x86-64
    :dir           "native/macos-x86-64"
    :artifact-name "com.outskirtslabs/vips-native-macos-x86-64"
    :sharp-package "@img/sharp-libvips-darwin-x64"
    :os            :macos
    :arch          :x86-64
    :libc          nil}
   {:platform-id   :macos-aarch64
    :dir           "native/macos-aarch64"
    :artifact-name "com.outskirtslabs/vips-native-macos-aarch64"
    :sharp-package "@img/sharp-libvips-darwin-arm64"
    :os            :macos
    :arch          :aarch64
    :libc          nil}
   {:platform-id   :win32-x86-64
    :dir           "native/win32-x86-64"
    :artifact-name "com.outskirtslabs/vips-native-win32-x86-64"
    :sharp-package "@img/sharp-libvips-win32-x64"
    :os            :windows
    :arch          :x86-64
    :libc          nil}])

(defn run-command
  [& args]
  (apply shell/sh (mapv str args)))

(defn assert-shell-ok
  [{:keys [exit out err] :as result}]
  (is (zero? exit) (str out err))
  result)

(defn platform-resource-root
  [platform]
  (fs/path (:dir platform)
           "resources"
           "ol"
           "vips"
           "native"
           (name (:platform-id platform))))

(defn manifest-path
  [platform]
  (fs/path (platform-resource-root platform) "manifest.edn"))

(defn read-manifest
  [platform]
  (-> (manifest-path platform)
      str
      slurp
      edn/read-string))

(defn jar-path
  [platform]
  (fs/path (:dir platform)
           "target"
           (format "%s-%s.jar"
                   (last (str/split (:artifact-name platform) #"/"))
                   root-version)))

(defn jar-entries
  [path]
  (with-open [jar (JarFile. (str path))]
    (into #{}
          (map #(.getName %))
          (enumeration-seq (.entries jar)))))

(defn mtime
  [path]
  (.toMillis (fs/last-modified-time path)))

(deftest root-project-pins-upstream-vips-version
  (is (string? pinned-vips-version))
  (is (not (str/blank? pinned-vips-version))))

(deftest native-workspace-layout
  (testing "each supported platform has a companion artifact directory"
    (doseq [{:keys [dir artifact-name]} expected-platforms]
      (let [deps-file  (fs/path dir "deps.edn")
            build-file (fs/path dir "build.clj")
            project    (some-> deps-file str slurp edn/read-string :aliases :neil :project)]
        (is (fs/exists? deps-file))
        (is (fs/exists? build-file))
        (is (= artifact-name (some-> project :name str)))
        (is (= root-version (:version project)))))))

(deftest native-update-stages-pinned-linux-platform
  (let [platform (first expected-platforms)]
    (assert-shell-ok (run-command "bb" "clean:native"))
    (assert-shell-ok (run-command "bb" "native:update" (name (:platform-id platform))))
    (testing "the staged resource tree contains the future loader contract"
      (let [resource-root (platform-resource-root platform)
            manifest      (read-manifest platform)
            package-json  (fs/path resource-root "upstream" "package.json")
            versions-json (fs/path resource-root "upstream" "versions.json")
            readme        (fs/path resource-root "upstream" "README.md")]
        (is (fs/exists? (manifest-path platform)))
        (is (fs/exists? package-json))
        (is (fs/exists? versions-json))
        (is (fs/exists? readme))
        (is (= (:platform-id platform) (:platform-id manifest)))
        (is (= (:artifact-name platform) (:artifact-name manifest)))
        (is (= (:sharp-package platform) (:sharp-package manifest)))
        (is (= pinned-vips-version (:vips-version manifest)))
        (is (= :linux (:os manifest)))
        (is (= :x86-64 (:arch manifest)))
        (is (= :glibc (:libc manifest)))
        (is (str/starts-with? (:source-url manifest) "https://registry.npmjs.org/"))
        (is (seq (:library-files manifest)))
        (doseq [library-file (:library-files manifest)]
          (is (fs/exists? (fs/path resource-root library-file))))))))

(deftest native-update-stages-macos-and-windows-platforms
  (let [platforms [(nth expected-platforms 5)
                   (nth expected-platforms 6)]]
    (assert-shell-ok (run-command "bb" "native:update"
                                  (name (:platform-id (first platforms)))
                                  (name (:platform-id (second platforms)))))
    (doseq [{:keys [platform-id os libc] :as platform} platforms]
      (let [manifest (read-manifest platform)]
        (is (= platform-id (:platform-id manifest)))
        (is (= os (:os manifest)))
        (is (= libc (:libc manifest)))
        (is (= pinned-vips-version (:vips-version manifest)))
        (is (seq (:library-files manifest)))))))

(deftest native-jar-contains-manifest-driven-layout
  (let [platform (first expected-platforms)]
    (assert-shell-ok (run-command "bb" "jar:native" (name (:platform-id platform))))
    (let [jar-file (jar-path platform)
          manifest (read-manifest platform)
          entries  (jar-entries jar-file)
          jar-root (format "ol/vips/native/%s/" (name (:platform-id platform)))]
      (is (fs/exists? jar-file))
      (is (contains? entries (str jar-root "manifest.edn")))
      (is (contains? entries (str jar-root "upstream/package.json")))
      (is (contains? entries (str jar-root "upstream/versions.json")))
      (is (contains? entries (str jar-root "upstream/README.md")))
      (doseq [library-file (:library-files manifest)]
        (is (contains? entries (str jar-root library-file)))))))

(deftest native-update-skips-unchanged-platform
  (let [platform      (first expected-platforms)
        manifest-file (manifest-path platform)]
    (assert-shell-ok (run-command "bb" "native:update" (name (:platform-id platform))))
    (let [initial-mtime (mtime manifest-file)]
      (Thread/sleep 1100)
      (let [{:keys [out]} (assert-shell-ok
                           (run-command "bb" "native:update" (name (:platform-id platform))))]
        (is (str/includes? out "native resources already up-to-date"))
        (is (= initial-mtime (mtime manifest-file)))))))

(deftest native-jar-skips-unchanged-platform
  (let [platform (first expected-platforms)
        jar-file (jar-path platform)]
    (assert-shell-ok (run-command "bb" "jar:native" (name (:platform-id platform))))
    (let [initial-mtime (mtime jar-file)]
      (Thread/sleep 1100)
      (let [{:keys [out]} (assert-shell-ok
                           (run-command "bb" "jar:native" (name (:platform-id platform))))]
        (is (str/includes? out "native jar up-to-date"))
        (is (= initial-mtime (mtime jar-file)))))))

(deftest linux-gnu-and-musl-remain-distinct
  (let [[gnu musl] (take 2 expected-platforms)]
    (assert-shell-ok (run-command "bb" "native:update"
                                  (name (:platform-id gnu))
                                  (name (:platform-id musl))))
    (let [gnu-manifest  (read-manifest gnu)
          musl-manifest (read-manifest musl)]
      (is (not= (:platform-id gnu-manifest) (:platform-id musl-manifest)))
      (is (= :glibc (:libc gnu-manifest)))
      (is (= :musl (:libc musl-manifest)))
      (is (not= (:artifact-name gnu-manifest) (:artifact-name musl-manifest)))
      (is (not= (:sharp-package gnu-manifest) (:sharp-package musl-manifest))))))
