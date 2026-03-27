(ns sharp-libvips.spike
  "Minimal FFM spike for proving that a sharp-libvips native bundle can be
  loaded directly from Clojure without going through vips-ffm.

  Usage:

    clojure -M:dev -m sharp-libvips.spike /abs/path/to/libvips-cpp.so.8.17.3

  Optional extra arguments are loaded first via `System/load`. This is useful
  on Nix-like systems where `libstdc++.so.6` is not on the default loader path:

    clojure -M:dev -m sharp-libvips.spike \\
      /abs/path/to/libvips-cpp.so.8.17.3 \\
      /abs/path/to/libstdc++.so.6"
  (:require
   [babashka.fs :as fs])
  (:import
   (java.lang.foreign Arena FunctionDescriptor Linker MemorySegment SymbolLookup ValueLayout)
   (java.lang.invoke MethodHandle)
   (java.nio.file Path)))

(defn- absolute-path ^String [pathish]
  (str (fs/absolutize pathish)))

(defn- load-library! [path]
  (let [absolute (absolute-path path)]
    (System/load absolute)
    absolute))

(defn- method-handle
  ^MethodHandle [^SymbolLookup lookup ^Linker linker symbol descriptor]
  (.downcallHandle linker
                   (.findOrThrow lookup symbol)
                   descriptor
                   (into-array java.lang.foreign.Linker$Option [])))

(defn- invoke
  [^MethodHandle handle & args]
  (.invokeWithArguments handle (object-array args)))

(defn- ensure-file! [path]
  (when-not (fs/exists? path)
    (throw (ex-info "Library path does not exist" {:path (absolute-path path)}))))

(defn- version-string [^MemorySegment segment]
  (-> segment
      (.reinterpret 64)
      (.getString 0)))

(defn -main [& args]
  (let [[libvips-cpp & preload-libs] args]
    (when-not libvips-cpp
      (throw (ex-info "Usage: clojure -M:dev -m sharp-libvips.spike /abs/path/to/libvips-cpp.so [preload-lib ...]"
                      {})))
    (ensure-file! libvips-cpp)
    (run! ensure-file! preload-libs)
    (doseq [lib preload-libs]
      (println "preloading" (load-library! lib)))
    (with-open [arena (Arena/ofConfined)]
      (let [lib-path ^Path (fs/path (absolute-path libvips-cpp))
            lookup (SymbolLookup/libraryLookup lib-path arena)
            linker (Linker/nativeLinker)
            app-name (.allocateFrom arena "ol.vips sharp-libvips spike")
            vips-init (method-handle lookup linker "vips_init"
                                     (FunctionDescriptor/of ValueLayout/JAVA_INT
                                                            (into-array java.lang.foreign.MemoryLayout
                                                                        [ValueLayout/ADDRESS])))
            vips-version (method-handle lookup linker "vips_version"
                                        (FunctionDescriptor/of ValueLayout/JAVA_INT
                                                               (into-array java.lang.foreign.MemoryLayout
                                                                           [ValueLayout/JAVA_INT])))
            vips-version-string (method-handle lookup linker "vips_version_string"
                                               (FunctionDescriptor/of ValueLayout/ADDRESS
                                                                      (into-array java.lang.foreign.MemoryLayout [])))
            vips-shutdown (method-handle lookup linker "vips_shutdown"
                                         (FunctionDescriptor/ofVoid (into-array java.lang.foreign.MemoryLayout [])))
            init-result (int (invoke vips-init app-name))]
        (when-not (zero? init-result)
          (throw (ex-info "vips_init failed" {:exit-code init-result})))
        (try
          (let [major (int (invoke vips-version (int 0)))
                minor (int (invoke vips-version (int 1)))
                micro (int (invoke vips-version (int 2)))
                version-ptr ^MemorySegment (invoke vips-version-string)]
            (println "libvips initialized from" (absolute-path libvips-cpp))
            (println "libvips version components:" [major minor micro])
            (println "libvips version string:" (version-string version-ptr)))
          (finally
            (invoke vips-shutdown)))))))
