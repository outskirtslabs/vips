(ns ol.vips-packaging-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.xml :as xml])
  (:import
   [java.util.jar JarFile]))

(defn- element-name
  [element]
  (some-> element :tag name keyword))

(defn- child-text
  [element child-name]
  (some (fn [child]
          (when (= child-name (element-name child))
            (apply str (filter string? (:content child)))))
        (:content element)))

(defn- dependencies
  [pom]
  (->> (tree-seq map? :content pom)
       (filter #(= :dependency (element-name %)))
       (map (fn [dependency]
              {:group-id    (child-text dependency :groupId)
               :artifact-id (child-text dependency :artifactId)
               :version     (child-text dependency :version)}))
       set))

(defn- jar-only-classpath
  [jar-path]
  (let [clojure-jars (->> (str/split (System/getProperty "java.class.path")
                                     (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
                          (filter #(re-find #"[/\\](clojure|spec\.alpha|core\.specs\.alpha)-[^/\\]+\.jar$" %)))
        native-paths (map #(str (io/file % "resources")) (.listFiles (io/file "native")))]
    (str/join java.io.File/pathSeparator (concat [jar-path] clojure-jars native-paths))))

(deftest main-jar-includes-ffi-runtime
  (let [project-version    (-> (slurp "deps.edn")
                               edn/read-string
                               (get-in [:aliases :neil :project :version]))
        jar-path           (format "target/vips-%s.jar" project-version)
        {:keys [exit err]} (shell/sh "clojure" "-T:build" "jar")]
    (is (zero? exit) err)
    (with-open [jar (JarFile. jar-path)
                pom (.getInputStream
                     jar
                     (.getJarEntry jar "META-INF/maven/com.outskirtslabs/vips/pom.xml"))]
      (testing "Git dependency sources, resources, and license ship in the Maven jar"
        (doseq [entry ["babashka/ffi.clj"
                       "clj-kondo.exports/babashka/ffi/config.edn"
                       "META-INF/licenses/babashka-ffi/LICENSE"]]
          (is (some? (.getJarEntry jar entry)) entry)))
      (testing "the POM requires neither Coffi nor an unresolvable FFI Maven artifact"
        (is (empty? (filter #(#{["org.suskalo" "coffi"] ["babashka" "ffi"]}
                              [(:group-id %) (:artifact-id %)])
                            (dependencies (xml/parse pom)))))))
    (testing "the public API runs with only the built jar, Clojure, and native resources"
      (let [expr                   '(do
                                      (require '[ol.vips :as v] '[ol.vips.operations :as ops])
                                      (with-open [image   (ops/black 3 2)
                                                  decoded (v/from-buffer (v/write-to-buffer image ".png"))]
                                        (prn (v/shape decoded))))
            {:keys [exit out err]} (shell/sh "java" "--enable-native-access=ALL-UNNAMED"
                                             "-cp" (jar-only-classpath jar-path)
                                             "clojure.main" "-e" (pr-str expr))]
        (is (zero? exit) err)
        (is (= [3 2 1] (edn/read-string out)))))))
