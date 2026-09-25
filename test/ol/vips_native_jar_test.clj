(ns ol.vips-native-jar-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [clojure.xml :as xml])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.util.jar JarFile]))

(deftest native-jars-have-no-runtime-dependencies-and-refresh-their-pom
  (let [root         (.toFile (Files/createTempDirectory "ol-vips-native-jar-" (make-array FileAttribute 0)))
        platform     "linux-x86-64-gnu"
        relative-dir (str "native/" platform)
        dir          (io/file root relative-dir)
        project      (-> (slurp (str relative-dir "/deps.edn"))
                         edn/read-string
                         (get-in [:aliases :neil :project]))
        artifact     (name (:name project))
        version      (:version project)
        pom-path     (str "META-INF/maven/com.outskirtslabs/" artifact "/pom.xml")
        repo-url     "https://github.com/outskirtslabs/vips"]
    (try
      (doseq [path ["deps.edn" "native/build_stub.clj"
                    (str relative-dir "/deps.edn")
                    (str relative-dir "/build.clj")]]
        (let [target (io/file root path)]
          (io/make-parents target)
          (io/copy (io/file path) target)))
      (let [resource (io/file dir "resources" "fixture.txt")]
        (io/make-parents resource)
        (spit resource "native resource fixture"))
      (doseq [revision ["1111111111111111111111111111111111111111"
                        "2222222222222222222222222222222222222222"]]
        (testing (str "build at Git revision " revision)
          (let [{:keys [exit out err]} (shell/sh "clojure" "-T:build" "jar"
                                                 :dir (.getPath dir)
                                                 :env (assoc (into {} (System/getenv)) "GIT_REV" revision))]
            (is (zero? exit) (str out err))
            (when (zero? exit)
              (with-open [jar        (JarFile. (io/file dir "target" (str artifact "-" version ".jar")))
                          pom-stream (.getInputStream jar (.getJarEntry jar pom-path))]
                (let [pom   (xml/parse pom-stream)
                      nodes (filter map? (xml-seq pom))
                      value (fn [tag]
                              (some #(when (= tag (:tag %)) (first (:content %))) nodes))]
                  (is (= {:dependency-count 0
                          :version          version
                          :revision         revision
                          :notice           (str repo-url "/blob/" revision "/THIRD-PARTY-NOTICES.md")}
                         {:dependency-count (count (filter #(= :dependency (:tag %)) nodes))
                          :version          (value :version)
                          :revision         (value :tag)
                          :notice           (value :thirdPartyNotice)}))))))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (io/delete-file file))))))
