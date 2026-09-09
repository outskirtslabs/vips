(ns ol.vips-packaging-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.util.jar JarFile]))

(deftest main-jar-does-not-bundle-dependency-runtime
  (let [project-version    (-> (slurp "deps.edn")
                               edn/read-string
                               (get-in [:aliases :neil :project :version]))
        jar-path           (format "target/vips-%s.jar" project-version)
        {:keys [exit err]} (shell/sh "clojure" "-T:build" "jar")]
    (is (zero? exit) err)
    (with-open [jar (JarFile. jar-path)]
      (testing "the jar contains the library and its Maven metadata"
        (doseq [entry ["ol/vips.clj"
                       "META-INF/maven/com.outskirtslabs/vips/pom.xml"
                       "META-INF/maven/com.outskirtslabs/vips/pom.properties"]]
          (is (some? (.getJarEntry jar entry)) entry)))
      (testing "the jar cannot shadow a separately resolved FFI dependency"
        (is (= []
               (->> (enumeration-seq (.entries jar))
                    (map #(.getName ^java.util.jar.JarEntry %))
                    (filter #(re-find #"^(babashka/|coffi/|clj-kondo\.exports/babashka/ffi/|META-INF/licenses/babashka-ffi/)" %))
                    vec)))))))
