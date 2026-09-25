;; Copyright © 2026 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(require
 '[clojure.edn :as edn]
 '[clojure.string :as str]
 '[clojure.tools.build.api :as b])

(def root-project (-> (edn/read-string (slurp "../../deps.edn"))
                      :aliases
                      :neil
                      :project))
(def repo-url-prefix (:url root-project))
(def project (-> (edn/read-string (slurp "deps.edn"))
                 :aliases
                 :neil
                 :project))
(def cwd (-> (java.io.File. ".") .getCanonicalFile .getName))

(defn- git-rev []
  (or (some-> (System/getenv "GIT_REV")
              str/trim
              not-empty)
      (some-> (b/git-process {:git-args "rev-parse HEAD"})
              str/trim
              not-empty)))

(def rev (git-rev))
(def lib (:name project))
(def version (:version project))
(def description (:description project))
(def license-id (-> project :license :id))
(def license-url (-> project :license :url))
(def notice-file (:notice-file project))

(assert lib ":name must be set in deps.edn under the :neil alias")
(assert version ":version must be set in deps.edn under the :neil alias")
(assert description ":description must be set in deps.edn under the :neil alias")
(assert license-id "[:license :id] must be set in deps.edn under the :neil alias")
(assert license-url "[:license :url] must be set in deps.edn under the :neil alias")
(assert rev "Either GIT_REV must be set or git rev-parse HEAD must succeed")

(def class-dir "target/classes")
(def basis (delay (b/create-basis {:root nil :project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn permalink [subpath]
  (str repo-url-prefix "/blob/" rev "/" subpath))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["resources"]
                :pom-data  (cond-> [[:description description]
                                    [:url (permalink (str "native/" cwd))]
                                    [:licenses
                                     [:license
                                      [:name license-id]
                                      [:url license-url]]]
                                    [:scm
                                     [:url repo-url-prefix]
                                     [:connection (str "scm:git:" repo-url-prefix)]
                                     [:tag rev]]]
                             notice-file
                             (conj [:properties
                                    [:thirdPartyNotice (permalink notice-file)]]))})
  (b/copy-dir {:src-dirs   ["resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn install [_]
  (jar {})
  (b/install {:basis     @basis
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
