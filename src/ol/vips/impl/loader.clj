(ns ^:no-doc ol.vips.impl.loader
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [coffi.ffi :as ffi])
  (:import
   [java.io InputStream PushbackReader RandomAccessFile]
   [java.lang.foreign Arena SymbolLookup]
   [java.net URL]
   [java.nio.file CopyOption Files Path Paths StandardCopyOption]
   [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(defn- read-platforms-resource
  []
  (with-open [reader (-> "ol/vips/impl/platforms.edn"
                         io/resource
                         io/reader
                         PushbackReader.)]
    (edn/read reader)))

(def supported-platforms
  (read-platforms-resource))

(def supported-platform-ids
  (mapv :platform-id supported-platforms))

(defn platform
  [platform-id]
  (or (some #(when (= platform-id (:platform-id %)) %) supported-platforms)
      (throw (ex-info "Unsupported native platform"
                      {:platform-id platform-id
                       :supported   supported-platform-ids}))))

(defn- path-of
  ^Path [first-segment & more-segments]
  (Paths/get (str first-segment) (into-array String (map str more-segments))))

(defn- absolute-path
  ^Path [pathish]
  (.toAbsolutePath
   (if (instance? Path pathish)
     ^Path pathish
     (path-of pathish))))

(defn- path-string
  ^String [pathish]
  (str (absolute-path pathish)))

(defn- path->lookup
  ^SymbolLookup [^String library-path ^Arena arena]
  (SymbolLookup/libraryLookup (absolute-path library-path)
                              arena))

(defn- library-lookup
  [library-paths]
  (let [arena   (Arena/ofShared)
        lookups (mapv #(path->lookup % arena) library-paths)]
    {:arena  arena
     :lookup (reduce (fn [^SymbolLookup acc ^SymbolLookup lookup]
                       (.or acc lookup))
                     lookups)}))

(defn- lookup-symbol
  [^SymbolLookup lookup symbol-name]
  (or (.orElse (.find lookup symbol-name) nil)
      (throw (ex-info "Native symbol not found in loaded ol.vips libraries"
                      {:symbol symbol-name}))))

(defn- loaded-symbol
  [symbol-name]
  (or (ffi/find-symbol symbol-name)
      (throw (ex-info "Native symbol not found in loaded ol.vips libraries"
                      {:symbol symbol-name}))))

(defn- present-string
  [value]
  (some-> value str/trim not-empty))

(defn- property-value
  [property-name]
  (present-string (System/getProperty property-name)))

(defn- env-value
  [env-name]
  (present-string (System/getenv env-name)))

(defn- mkdirs!
  ^Path [path]
  (Files/createDirectories ^Path path (make-array FileAttribute 0)))

(defn- copy!
  [^InputStream in ^Path target]
  (mkdirs! (.getParent target))
  (let [^"[Ljava.nio.file.CopyOption;" copy-options
        (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])]
    (Files/copy in target copy-options))
  target)

(defn- resource-path
  [platform-id relative-path]
  (format "ol/vips/native/%s/%s" (name platform-id) relative-path))

(defn- resource-url
  ^URL [platform-id relative-path]
  (or (io/resource (resource-path platform-id relative-path))
      (throw (ex-info "Native resource not found on classpath"
                      {:platform-id   platform-id
                       :resource-path (resource-path platform-id relative-path)}))))

(defn- slurp-edn-resource
  [platform-id relative-path]
  (with-open [reader (-> (resource-url platform-id relative-path)
                         io/reader
                         PushbackReader.)]
    (edn/read reader)))

(defn normalize-os
  [os-name]
  (let [value (-> os-name str str/lower-case)]
    (cond
      (or (str/includes? value "linux")
          (= value "gnu/linux")) :linux
      (or (str/includes? value "mac")
          (str/includes? value "darwin")
          (str/includes? value "os x")) :macos
      (str/includes? value "win") :windows
      :else
      (throw (ex-info "Unsupported operating system"
                      {:os-name os-name})))))

(defn normalize-arch
  [arch-name]
  (let [value (-> arch-name str str/lower-case)]
    (cond
      (#{"x86_64" "x86-64" "amd64" "x64"} value) :x86-64
      (#{"aarch64" "arm64"} value) :aarch64
      :else
      (throw (ex-info "Unsupported CPU architecture"
                      {:arch-name arch-name})))))

(defn- read-proc-maps
  []
  (with-open [raf (RandomAccessFile. "/proc/self/maps" "r")]
    (loop [lines []]
      (if-let [line (.readLine raf)]
        (recur (conj lines line))
        (str/join "\n" lines)))))

(defn detect-libc
  []
  (let [maps (read-proc-maps)]
    (cond
      (re-find #"libc\.musl-|ld-musl-" maps) :musl
      (re-find #"libc-2\.|libc\.so\.6|ld-linux" maps) :glibc
      :else
      (throw (ex-info "Could not determine Linux libc" {})))))

(defn detect-host-platform
  []
  (let [platform-id-override (property-value "ol.vips.native.platform-id")
        os-name              (or (property-value "ol.vips.native.os")
                                 (System/getProperty "os.name"))
        arch-name            (or (property-value "ol.vips.native.arch")
                                 (System/getProperty "os.arch"))]
    (if platform-id-override
      (platform (keyword platform-id-override))
      (let [os   (normalize-os os-name)
            arch (normalize-arch arch-name)
            libc (when (= :linux os)
                   (if-let [override (property-value "ol.vips.native.libc")]
                     (keyword override)
                     (detect-libc)))]
        (or (some (fn [{platform-os   :os
                        platform-arch :arch
                        platform-libc :libc
                        :as           platform}]
                    (when (= [platform-os platform-arch platform-libc]
                             [os arch libc])
                      platform))
                  supported-platforms)
            (throw (ex-info "Unsupported native platform"
                            {:os        os
                             :arch      arch
                             :libc      libc
                             :supported supported-platform-ids})))))))

(defn manifest-resource-path
  ([] (manifest-resource-path (:platform-id (detect-host-platform))))
  ([platform-id]
   (resource-path platform-id "manifest.edn")))

(defn read-manifest
  ([] (read-manifest (:platform-id (detect-host-platform))))
  ([platform-id]
   (slurp-edn-resource platform-id "manifest.edn")))

(defn default-cache-root
  ^Path []
  (let [override  (property-value "ol.vips.native.cache-root")
        xdg-home  (env-value "XDG_CACHE_HOME")
        user-home (property-value "user.home")
        tmp-dir   (System/getProperty "java.io.tmpdir")]
    (cond
      override (absolute-path override)
      xdg-home (path-of xdg-home "ol.vips")
      user-home (path-of user-home ".cache" "ol.vips")
      :else (path-of tmp-dir "ol.vips"))))

(defn preload-library-paths
  []
  (when-let [value (property-value "ol.vips.native.preload")]
    (->> (str/split value (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn system-library-names
  []
  (if-let [value (property-value "ol.vips.native.system-libs")]
    (->> (str/split value (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
         (map str/trim)
         (remove str/blank?)
         distinct
         vec)
    ["vips-cpp" "vips"]))

(defn extraction-root
  ([manifest]
   (extraction-root (default-cache-root) manifest))
  ([cache-root manifest]
   (absolute-path
    (path-of cache-root
             (name (:platform-id manifest))
             (str (:vips-version manifest) "-" (:sharp-version manifest))))))

(defn extracted-library-paths
  ([manifest]
   (extracted-library-paths (default-cache-root) manifest))
  ([cache-root manifest]
   (mapv (fn [relative-path]
           (path-string (path-of (extraction-root cache-root manifest) relative-path)))
         (:library-files manifest))))

(defn extract-libraries!
  ([]
   (extract-libraries! (default-cache-root) (read-manifest)))
  ([cache-root manifest]
   (let [root (extraction-root cache-root manifest)]
     (mkdirs! root)
     (doseq [relative-path (:library-files manifest)]
       (with-open [in (io/input-stream (resource-url (:platform-id manifest) relative-path))]
         (copy! in (path-of root relative-path))))
     {:platform-id     (:platform-id manifest)
      :manifest        manifest
      :cache-root      (path-string (absolute-path cache-root))
      :extraction-root (path-string root)
      :library-paths   (extracted-library-paths cache-root manifest)
      :resource-root   (format "ol/vips/native/%s/" (name (:platform-id manifest)))})))

(defn expose-paths!
  [{:keys [platform-id cache-root extraction-root library-paths] :as state}]
  (System/setProperty "ol.vips.native.platform-id" (name platform-id))
  (System/setProperty "ol.vips.native.cache-root" cache-root)
  (System/setProperty "ol.vips.native.extraction-root" extraction-root)
  (System/setProperty "ol.vips.native.library-paths" (pr-str library-paths))
  (when-let [primary-library-path (first library-paths)]
    (System/setProperty "ol.vips.native.primary-library-path" primary-library-path))
  state)

(defn- load-system-libraries!
  []
  (let [attempts
        (system-library-names)

        {:keys [loaded failures]}
        (reduce (fn [{:keys [loaded failures] :as acc} lib]
                  (try
                    (ffi/load-system-library lib)
                    (assoc acc :loaded (conj loaded lib))
                    (catch Throwable t
                      (assoc acc :failures (conj failures {:library lib
                                                           :cause   t})))))
                {:loaded [] :failures []}
                attempts)]
    (if (seq loaded)
      loaded
      (throw (ex-info "Failed to load libvips from the system library path"
                      {:libraries (vec attempts)
                       :failures  (mapv (fn [{:keys [library cause]}]
                                          {:library library
                                           :message (ex-message cause)
                                           :class   (.getName (class cause))})
                                        failures)})))))

(defn- load-packaged-native-state
  []
  (let [manifest               (read-manifest)
        extracted              (extract-libraries! (default-cache-root) manifest)
        load-paths             (vec (concat (preload-library-paths) (:library-paths extracted)))
        _                      (doseq [path load-paths]
                                 (ffi/load-library path))
        {:keys [arena lookup]} (library-lookup load-paths)
        exposed                (expose-paths! extracted)]
    (merge exposed
           {:lookup               lookup
            :lookup-arena         arena
            :manifest             manifest
            :primary-library-path (first (:library-paths extracted))
            :native-load-source   :packaged
            :resolve-symbol       #(lookup-symbol lookup %)})))

(defn- load-system-native-state
  [cause]
  (let [loaded-libraries (load-system-libraries!)]
    {:lookup                     nil
     :lookup-arena               nil
     :manifest                   nil
     :primary-library-path       nil
     :native-load-source         :system
     :native-load-fallback-from  :packaged
     :native-load-fallback-cause (ex-message cause)
     :native-load-fallback-class (.getName (class cause))
     :system-library-names       loaded-libraries
     :resolve-symbol             loaded-symbol}))

(defn load-native!
  []
  (try
    (load-packaged-native-state)
    (catch Throwable packaged-load-failure
      (load-system-native-state packaged-load-failure))))
