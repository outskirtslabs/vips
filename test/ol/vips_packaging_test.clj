(ns ol.vips-packaging-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
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

(deftest main-jar-pom-declares-coffi
  (testing "the published main jar carries its Coffi runtime dependency"
    (let [project-version    (-> (slurp "deps.edn")
                                 edn/read-string
                                 (get-in [:aliases :neil :project :version]))
          jar-path           (format "target/vips-%s.jar" project-version)
          {:keys [exit err]} (shell/sh "clojure" "-T:build" "jar")]
      (is (zero? exit) err)
      (with-open [jar (JarFile. jar-path)
                  pom (.getInputStream
                       jar
                       (.getJarEntry
                        jar
                        "META-INF/maven/com.outskirtslabs/vips/pom.xml"))]
        (is (contains? (dependencies (xml/parse pom))
                       {:group-id    "org.suskalo"
                        :artifact-id "coffi"
                        :version     "1.0.615"}))))))
