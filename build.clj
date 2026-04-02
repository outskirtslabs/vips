;; Copyright © 2026 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns build
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [ol.vips.native.platforms :as native.platforms])
  (:import
   (java.net URI URLEncoder)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.nio.file Files)))

(def project (-> (edn/read-string (slurp "deps.edn")) :aliases :neil :project))
(def lib (:name project))
(def version (:version project))
(def license-id (-> project :license :id))
(def license-file (or (-> project :license :file) "LICENSE"))
(def description (:description project))
(def vips-version (:vips-version project))

(def native-root "native")
(def native-cache-root "target/native-cache")
(def native-resolution-cache-file (str native-cache-root "/resolution.edn"))
(def native-version-probe-package "@img/sharp-libvips-linux-x64")
(def native-library-pattern #".*\.(?:dll|dylib|so(?:\..+)?)$")

(defn- git-origin-url []
  (try
    (some-> (b/git-process {:git-args "remote get-url origin"})
            str/trim
            (str/replace #"\.git$" ""))
    (catch Exception _
      nil)))

(defn- git-rev []
  (or (some-> (System/getenv "GIT_REV")
              str/trim
              not-empty)
      (some-> (b/git-process {:git-args "rev-parse HEAD"})
              str/trim
              not-empty)))

(def rev (git-rev))
(def repo-url-prefix (or (:url project) (git-origin-url)))

(assert lib ":name must be set in deps.edn under the :neil alias")
(assert version ":version must be set in deps.edn under the :neil alias")
(assert description ":description must be set in deps.edn under the :neil alias")
(assert license-id "[:license :id] must be set in deps.edn under the :neil alias")
(assert vips-version "[:vips-version] must be set in deps.edn under the :neil alias")
(assert rev "Either GIT_REV must be set or git rev-parse HEAD must succeed")
(assert repo-url-prefix "Either :url must be set in deps.edn under the :neil alias or git remote origin must exist")

(def class-dir "target/classes")
(def basis_ (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def ^HttpClient http-client (-> (HttpClient/newBuilder) (.build)))

(defn- existing-paths [paths]
  (->> paths
       (filter #(.exists (io/file %)))
       vec))

(defn permalink [subpath]
  (str repo-url-prefix "/blob/" rev "/" subpath))

(defn url->scm [url-string]
  (let [[_ domain repo-path] (re-find #"https?://?([\w\-\.]+)/(.+)" url-string)]
    [:scm
     [:url (str "https://" domain "/" repo-path)]
     [:connection (str "scm:git:https://" domain "/" repo-path)]
     [:developerConnection (str "scm:git:ssh:git@" domain ":" repo-path)]]))

(defn- mkdirs!
  [path]
  (.mkdirs (io/file path))
  path)

(defn- safe-slug
  [value]
  (str/replace value #"[^A-Za-z0-9._-]+" "_"))

(defn- encode-package-name
  [package-name]
  (-> (URLEncoder/encode package-name "UTF-8")
      (str/replace "+" "%20")))

(defn- native-platform-id
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else
    (throw (ex-info "Platform id must be a keyword or string"
                    {:value value
                     :type  (some-> value class .getName)}))))

(defn- selected-platforms
  [platforms]
  (mapv native.platforms/platform
        (if (seq platforms)
          (map native-platform-id platforms)
          native.platforms/supported-platform-ids)))

(defn- platform-dir
  [{:keys [dir-name]}]
  (str (io/file native-root dir-name)))

(defn- platform-resources-dir
  [platform]
  (str (io/file (platform-dir platform) "resources")))

(defn- platform-resource-root
  [{:keys [platform-id] :as platform}]
  (str (io/file (platform-resources-dir platform)
                "ol"
                "vips"
                "native"
                (name platform-id))))

(defn- native-deps-file
  [platform]
  (str (io/file (platform-dir platform) "deps.edn")))

(defn- native-build-file
  [platform]
  (str (io/file (platform-dir platform) "build.clj")))

(defn- manifest-file
  [platform]
  (str (io/file (platform-resource-root platform) "manifest.edn")))

(defn- npm-package-registry-url
  [package-name]
  (str "https://registry.npmjs.org/" (encode-package-name package-name)))

(defn- http-get-text
  [url]
  (let [request  (-> (HttpRequest/newBuilder (URI/create url)) (.GET) (.build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode response) 299)
      (throw (ex-info "HTTP request failed"
                      {:url         url
                       :status-code (.statusCode response)})))
    (.body response)))

(defn- registry-package-metadata
  [package-name]
  (-> package-name
      npm-package-registry-url
      http-get-text
      json/read-str))

(defn- tarball-cache-path
  [package-name sharp-version]
  (str (io/file native-cache-root
                "downloads"
                (safe-slug package-name)
                (str sharp-version ".tgz"))))

(defn- download-file!
  [url path]
  (let [path-file (io/file path)]
    (mkdirs! (.getParent path-file))
    (when-not (.exists path-file)
      (let [request  (-> (HttpRequest/newBuilder (URI/create url)) (.GET) (.build))
            response (.send http-client
                            request
                            (HttpResponse$BodyHandlers/ofFile (.toPath path-file)))]
        (when-not (<= 200 (.statusCode response) 299)
          (throw (ex-info "Download failed"
                          {:url         url
                           :path        path
                           :status-code (.statusCode response)})))))
    path))

(defn- untar!
  [archive-path target-dir]
  (mkdirs! target-dir)
  (let [{:keys [exit err]} (shell/sh "tar" "-xzf" archive-path "-C" target-dir)]
    (when-not (zero? exit)
      (throw (ex-info "Failed to unpack native archive"
                      {:archive archive-path
                       :target  target-dir
                       :stderr  err})))))

(defn- with-temp-dir
  [prefix f]
  (let [dir (str (Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f dir)
      (finally
        (b/delete {:path dir})))))

(defn- parse-int
  [value]
  (Integer/parseInt value))

(defn- parse-semver
  [version-string]
  (let [[_ major minor patch prerelease]
        (re-matches #"(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?" version-string)]
    (when-not major
      (throw (ex-info "Unsupported semver string"
                      {:version version-string})))
    {:major      (parse-int major)
     :minor      (parse-int minor)
     :patch      (parse-int patch)
     :prerelease (some-> prerelease (str/split #"\."))}))

(defn- prerelease-part
  [part]
  (if (re-matches #"\d+" part)
    {:type :number :value (parse-int part)}
    {:type :string :value part}))

(defn- compare-prerelease
  [left right]
  (cond
    (and (nil? left) (nil? right)) 0
    (nil? left) 1
    (nil? right) -1
    :else
    (loop [[left-part & left-rest]   left
           [right-part & right-rest] right]
      (cond
        (and (nil? left-part) (nil? right-part)) 0
        (nil? left-part) -1
        (nil? right-part) 1
        :else
        (let [{left-type :type left-value :value}   (prerelease-part left-part)
              {right-type :type right-value :value} (prerelease-part right-part)
              result                                (cond
                                                      (and (= left-type :number) (= right-type :number))
                                                      (compare left-value right-value)

                                                      (= left-type right-type)
                                                      (compare left-value right-value)

                                                      (= left-type :number)
                                                      -1

                                                      :else
                                                      1)]
          (if (zero? result)
            (recur left-rest right-rest)
            result))))))

(defn- compare-semver
  [left right]
  (let [{left-major :major left-minor :minor left-patch :patch left-prerelease :prerelease}
        (parse-semver left)
        {right-major :major right-minor :minor right-patch :patch right-prerelease :prerelease}
        (parse-semver right)]
    (or (some identity
              (remove zero?
                      [(compare left-major right-major)
                       (compare left-minor right-minor)
                       (compare left-patch right-patch)
                       (compare-prerelease left-prerelease right-prerelease)]))
        0)))

(defn- semver-desc
  [left right]
  (pos? (compare-semver left right)))

(defn- cached-resolution
  []
  (when (.exists (io/file native-resolution-cache-file))
    (edn/read-string (slurp native-resolution-cache-file))))

(defn- cache-resolution!
  [resolution]
  (mkdirs! (.getParent (io/file native-resolution-cache-file)))
  (spit native-resolution-cache-file (with-out-str (pprint/pprint resolution)))
  resolution)

(defn- unpacked-package-info
  [archive-path]
  (with-temp-dir "ol-vips-native-"
    (fn [temp-dir]
      (untar! archive-path temp-dir)
      (let [package-dir   (str (io/file temp-dir "package"))
            versions-file (str (io/file package-dir "versions.json"))]
        (when-not (.exists (io/file versions-file))
          (throw (ex-info "versions.json missing from sharp-libvips archive"
                          {:archive archive-path})))
        {:package-dir    package-dir
         :versions-data  (-> versions-file slurp json/read-str)
         :metadata-files {:package-json (str (io/file package-dir "package.json"))
                          :versions     versions-file
                          :readme       (str (io/file package-dir "README.md"))}}))))

(defn- resolve-sharp-version
  []
  (let [cached (cached-resolution)]
    (if (= vips-version (:vips-version cached))
      (do
        (println "using cached sharp-libvips version" (:sharp-version cached)
                 "for libvips" vips-version)
        cached)
      (let [versions-map                                                                                (get (registry-package-metadata native-version-probe-package) "versions")
            resolved
            (some (fn [sharp-version]
                    (let [version-info (get versions-map sharp-version)
                          tarball-url  (get-in version-info ["dist" "tarball"])
                          archive-path (tarball-cache-path native-version-probe-package sharp-version)]
                      (println "checking sharp-libvips version" sharp-version
                               "for libvips" vips-version)
                      (download-file! tarball-url archive-path)
                      (let [{versions-data :versions-data} (unpacked-package-info archive-path)
                            packaged-vips-version          (get versions-data "vips")]
                        (when (= packaged-vips-version vips-version)
                          (println "resolved sharp-libvips version" sharp-version
                                   "for libvips" vips-version)
                          {:vips-version  vips-version
                           :sharp-version sharp-version}))))
                  (sort semver-desc (keys versions-map)))]
        (or (some-> resolved cache-resolution!)
            (throw (ex-info "Unable to resolve sharp-libvips version for pinned libvips version"
                            {:vips-version vips-version})))))))

(defn- registry-version-info
  [package-name sharp-version]
  (or (get-in (registry-package-metadata package-name) ["versions" sharp-version])
      (throw (ex-info "sharp-libvips version missing for platform package"
                      {:package-name  package-name
                       :sharp-version sharp-version
                       :vips-version  vips-version}))))

(defn- native-library-files
  [package-dir]
  (let [package-path (.toPath (io/file package-dir))]
    (->> (file-seq (io/file package-dir "lib"))
         (filter #(.isFile ^java.io.File %))
         (map #(.toPath ^java.io.File %))
         (map (fn [path]
                (-> (.relativize package-path path)
                    str
                    (str/replace java.io.File/separator "/"))))
         (filter #(re-matches native-library-pattern %))
         sort
         vec)))

(defn- validate-upstream-package!
  [platform package-dir versions-data library-files]
  (let [required-paths [(str (io/file package-dir "lib"))
                        (str (io/file package-dir "package.json"))
                        (str (io/file package-dir "versions.json"))
                        (str (io/file package-dir "README.md"))]]
    (doseq [path required-paths]
      (when-not (.exists (io/file path))
        (throw (ex-info "Missing required file in sharp-libvips archive"
                        {:platform-id (:platform-id platform)
                         :path        path}))))
    (when-not (= vips-version (get versions-data "vips"))
      (throw (ex-info "Resolved sharp-libvips archive does not match pinned libvips version"
                      {:platform-id           (:platform-id platform)
                       :expected-vips-version vips-version
                       :actual-vips-version   (get versions-data "vips")})))
    (when-not (seq library-files)
      (throw (ex-info "No native libraries found in sharp-libvips archive"
                      {:platform-id (:platform-id platform)
                       :package-dir package-dir})))))

(defn- copy-file!
  [source target]
  (mkdirs! (.getParent (io/file target)))
  (io/copy (io/file source) (io/file target))
  target)

(defn- manifest-data
  [platform sharp-version tarball-url versions-data library-files]
  {:platform-id   (:platform-id platform)
   :artifact-name (:artifact-name platform)
   :sharp-package (:sharp-package platform)
   :sharp-version sharp-version
   :vips-version  (get versions-data "vips")
   :libc          (:libc platform)
   :os            (:os platform)
   :arch          (:arch platform)
   :library-files library-files
   :source-url    tarball-url})

(defn- write-manifest!
  [platform manifest]
  (mkdirs! (.getParent (io/file (manifest-file platform))))
  (spit (manifest-file platform) (with-out-str (pprint/pprint manifest))))

(defn- staged-native-files-present?
  [platform manifest]
  (let [resource-root (platform-resource-root platform)]
    (every? (fn [path]
              (.exists (io/file resource-root path)))
            (concat ["upstream/package.json"
                     "upstream/versions.json"
                     "upstream/README.md"
                     "manifest.edn"]
                    (:library-files manifest)))))

(defn- expected-staged-manifest
  [{:keys [platform-id artifact-name sharp-package libc os arch]} sharp-version tarball-url]
  {:platform-id   platform-id
   :artifact-name artifact-name
   :sharp-package sharp-package
   :sharp-version sharp-version
   :vips-version  vips-version
   :libc          libc
   :os            os
   :arch          arch
   :source-url    tarball-url})

(defn- staged-manifest-up-to-date?
  [platform sharp-version tarball-url]
  (let [manifest-path* (manifest-file platform)
        expected       (expected-staged-manifest platform sharp-version tarball-url)]
    (when (.exists (io/file manifest-path*))
      (let [manifest (-> manifest-path* slurp edn/read-string)]
        (when (and (= expected (select-keys manifest (keys expected)))
                   (staged-native-files-present? platform manifest))
          manifest)))))

(defn- validate-staged-platform!
  [platform manifest]
  (let [resource-root (platform-resource-root platform)]
    (doseq [path (concat ["upstream/package.json"
                          "upstream/versions.json"
                          "upstream/README.md"]
                         (:library-files manifest)
                         ["manifest.edn"])]
      (when-not (.exists (io/file resource-root path))
        (throw (ex-info "Missing staged native resource"
                        {:platform-id (:platform-id platform)
                         :path        path}))))))

(defn- stage-platform!
  [platform sharp-version]
  (let [version-info (registry-version-info (:sharp-package platform) sharp-version)
        tarball-url  (get-in version-info ["dist" "tarball"])
        archive-path (download-file! tarball-url
                                     (tarball-cache-path (:sharp-package platform) sharp-version))]
    (if-let [manifest (staged-manifest-up-to-date? platform sharp-version tarball-url)]
      (do
        (println "native resources already up-to-date for" (name (:platform-id platform)))
        manifest)
      (do
        (println "staging" (name (:platform-id platform))
                 "from" (:sharp-package platform)
                 "@" sharp-version)
        (with-temp-dir "ol-vips-native-stage-"
          (fn [temp-dir]
            (untar! archive-path temp-dir)
            (let [package-dir   (str (io/file temp-dir "package"))
                  versions-data (-> (str (io/file package-dir "versions.json"))
                                    slurp
                                    json/read-str)
                  library-files (native-library-files package-dir)
                  manifest      (manifest-data platform sharp-version tarball-url versions-data library-files)
                  resource-root (platform-resource-root platform)]
              (validate-upstream-package! platform package-dir versions-data library-files)
              (b/delete {:path (platform-resources-dir platform)})
              (mkdirs! resource-root)
              (b/copy-dir {:src-dirs   [(str (io/file package-dir "lib"))]
                           :target-dir (str (io/file resource-root "lib"))})
              (copy-file! (str (io/file package-dir "package.json"))
                          (str (io/file resource-root "upstream" "package.json")))
              (copy-file! (str (io/file package-dir "versions.json"))
                          (str (io/file resource-root "upstream" "versions.json")))
              (copy-file! (str (io/file package-dir "README.md"))
                          (str (io/file resource-root "upstream" "README.md")))
              (write-manifest! platform manifest)
              (validate-staged-platform! platform manifest)
              manifest)))))))

(defn- native-deps-data
  [platform]
  {:paths   ["resources"]
   :aliases {:build {:deps       '{io.github.clojure/tools.build {:mvn/version "0.10.13"}
                                   slipset/deps-deploy           {:mvn/version "0.2.2"}}
                     :ns-default 'build}
             :neil  {:project {:name        (symbol (:artifact-name platform))
                               :description (str "Platform-native libvips bundle for "
                                                 (name lib)
                                                 " ("
                                                 (name (:platform-id platform))
                                                 ")")
                               :version     version}}}})

(defn sync-native-versions
  [_]
  (doseq [platform native.platforms/supported-platforms]
    (let [deps-path (native-deps-file platform)
          content   (with-out-str (pprint/pprint (native-deps-data platform)))]
      (mkdirs! (platform-dir platform))
      (when (not= content (when (.exists (io/file deps-path))
                            (slurp deps-path)))
        (spit deps-path content))))
  (println "synced native companion versions to" version)
  {:version version
   :updated (mapv :platform-id native.platforms/supported-platforms)})

(defn clean [_]
  (b/delete {:path "target"}))

(defn native-clean [_]
  (doseq [platform native.platforms/supported-platforms]
    (b/delete {:path (platform-resources-dir platform)})
    (b/delete {:path (str (io/file (platform-dir platform) "target"))}))
  (b/delete {:path native-cache-root}))

(defn native-update
  [{:keys [platforms]}]
  (sync-native-versions nil)
  (let [sharp-version (:sharp-version (resolve-sharp-version))
        targets       (selected-platforms platforms)]
    (println "updating native resources for"
             (str/join ", " (map (comp name :platform-id) targets)))
    {:sharp-version sharp-version
     :platforms     (mapv (fn [platform]
                            {:platform-id (:platform-id platform)
                             :manifest    (stage-platform! platform sharp-version)})
                          targets)}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis_
                :src-dirs  (existing-paths ["src"])
                :pom-data  [[:description description]
                            [:url repo-url-prefix]
                            [:licenses
                             [:license
                              [:name license-id]
                              [:url (permalink license-file)]]]
                            (conj (url->scm repo-url-prefix) [:tag rev])]})
  (b/copy-dir {:src-dirs   (existing-paths ["src" "resources"])
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn install [_]
  (jar {})
  (b/install {:basis     @basis_
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir}))

(defn deploy [opts]
  (jar opts)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   (merge {:installer :remote
           :artifact  jar-file
           :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}
          opts))
  opts)
