(ns script.native-image
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(let [[platform ffi-checkout] *command-line-args*
      graalvm                 (System/getenv "GRAALVM_HOME")
      out                     (fs/path "target" "native-image")
      classes                 (fs/path out "classes")
      image                   (fs/path out "vips-native-smoke")
      project                 (edn/read-string (slurp "deps.edn"))
      ffi-version             (get-in project [:deps 'org.babashka/ffi :mvn/version])]
  (when-not (and graalvm platform ffi-checkout
                 (fs/exists? (fs/path "native" platform "deps.edn")))
    (throw (ex-info "Set GRAALVM_HOME and pass a platform ID and a babashka/ffi Git checkout" {})))
  (fs/delete-tree out)
  (fs/create-dirs classes)
  (p/shell "bb" "native:update" platform)
  (let [deps-cp    (str/trim (:out (p/shell {:out :string} "clojure" "-Spath" "-A:native-image")))
        cp         (str/join fs/path-separator
                             [(str classes) deps-cp (str (fs/path "native" platform "resources"))])
        java-files (mapv (fn [name]
                           (let [relative (str "babashka/ffi/impl/" name ".java")
                                 file     (fs/path out "java" relative)
                                 source   (:out (p/shell {:out :string} "git" "-C" ffi-checkout
                                                         "show" (str "v" ffi-version ":src-java/" relative)))]
                             (fs/create-dirs (fs/parent file))
                             (spit (str file) source)
                             (str file)))
                         ["FfiTrampoline" "FfiTrampolineOrdered" "Libffi"])]
    (apply p/shell (str (fs/path graalvm "bin" "javac"))
           "--release" "25" "-cp" cp "-d" (str classes) java-files)
    (p/shell "clojure" "-Scp" cp "-J--enable-native-access=ALL-UNNAMED"
             "-M" "-e" (pr-str (list 'binding ['*compile-path* (str classes)]
                                     '(compile 'ol.vips.native-image-smoke))))
    (p/shell {:extra-env {"BABASHKA_FEATURE_LIBFFI" "true"}}
             (str (fs/path graalvm "bin" "native-image"))
             "-cp" cp
             "--features=clj_easy.graal_build_time.InitClojureClasses"
             "-H:+UnlockExperimentalVMOptions"
             "-H:+ForeignAPISupport"
             "-H:+SharedArenaSupport"
             "--enable-native-access=ALL-UNNAMED"
             "--no-fallback"
             "-EBABASHKA_FEATURE_LIBFFI"
             (str "-H:NativeLinkerOption=" (or (System/getenv "BABASHKA_LIBFFI") "-lffi"))
             "-J-Xmx6g" "--parallelism=2" "-O1"
             "-o" (str image) "ol.vips.native_image_smoke"))
  (let [run-dir    (fs/create-temp-dir {:prefix "ol-vips-native-run-"})
        executable (fs/path run-dir "vips-native-smoke")]
    (try
      (fs/copy image executable {:copy-attributes true})
      (p/shell {:dir       (str run-dir)
                :extra-env {"CLASSPATH" "" "JAVA_HOME" "" "PATH" ""}}
               (str executable)
               (str "-Dol.vips.native.cache-root=" (fs/path run-dir "cache")))
      (finally
        (fs/delete-tree run-dir)))))
