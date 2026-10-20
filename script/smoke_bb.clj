(ns script.smoke-bb
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

(defn -main
  "Tests `main-jar` and `native-jar` outside the checkout in a fresh Babashka process."
  [main-jar native-jar]
  (let [jars (mapv (comp str fs/absolutize) [main-jar native-jar])]
    (doseq [jar jars]
      (when-not (fs/regular-file? jar)
        (throw (ex-info "Artifact jar does not exist" {:path jar}))))
    (let [run-dir (fs/create-temp-dir {:prefix "ol-vips-bb-artifacts-"})]
      (try
        (fs/create-dirs (fs/path run-dir "ol" "vips"))
        (fs/copy "test-native/ol/vips/runtime_smoke_test.clj"
                 (fs/path run-dir "ol" "vips" "runtime_smoke_test.clj"))
        (let [{:keys [exit out err]}
              (shell/sh "bb" "--classpath" (str/join fs/path-separator (conj jars (str run-dir)))
                        (str "-Dol.vips.native.cache-root=" (fs/path run-dir "cache"))
                        "-m" "ol.vips.runtime-smoke-test"
                        :dir (str run-dir)
                        :env (assoc (into {} (System/getenv)) "CLASSPATH" ""))]
          (print out)
          (binding [*out* *err*] (print err))
          (when-not (zero? exit)
            (throw (ex-info "Babashka artifact smoke test failed" {:exit exit}))))
        (finally
          (fs/delete-tree run-dir))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
