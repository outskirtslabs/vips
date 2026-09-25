(ns ol.vips-reload-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(def ^:private reload-probe
  (pr-str
   '(do
      (require 'ol.vips 'ol.vips.operations 'clj-reload.core)
      (let [before              ((requiring-resolve 'ol.vips/init!))
            image-handle-before @(requiring-resolve 'ol.vips.impl.api/image-handle)
            close-count         (atom 0)]
        (with-open [held     ((requiring-resolve 'ol.vips.operations/black) 8 6)
                    source   (proxy [java.io.ByteArrayInputStream]
                                    [((requiring-resolve 'ol.vips/write-to-buffer) held ".png")]
                               (close [] (swap! close-count inc)))
                    streamed ((requiring-resolve 'ol.vips/from-stream) source)
                    bridge   ((requiring-resolve 'ol.vips.impl.api/new-target-bridge)
                              (java.io.ByteArrayOutputStream.))
                    result   ((requiring-resolve 'ol.vips.impl.api/operation-result)
                              {:out ((requiring-resolve 'ol.vips.operations/black) 8 6)
                               :tag :owned-image})]
          (assert (= {:width 8 :height 6 :bands 1 :has-alpha? false}
                     ((requiring-resolve 'ol.vips/metadata) held)))
          (when-let [aot-path (System/getProperty "ol.vips.test.aot-path")]
            (assert (= (.toURI (java.io.File. aot-path))
                       (-> held class .getProtectionDomain .getCodeSource .getLocation .toURI))))
          (binding [*out* *err*]
            (when (= "clj-reload" (System/getProperty "ol.vips.test.reload"))
              ((requiring-resolve 'clj-reload.core/init) {:dirs ["src"]}))
            (dotimes [_ 3]
              (case (System/getProperty "ol.vips.test.reload")
                "none" nil
                "require" (do
                            (require 'ol.vips.impl.handles :reload)
                            (require 'ol.vips.impl.loader :reload)
                            (require 'ol.vips.impl.api :reload))
                "require-all" (require 'ol.vips :reload-all)
                "source" (do
                           (load-file "src/ol/vips/impl/handles.clj")
                           (require 'ol.vips.impl.api :reload))
                "clj-reload" ((requiring-resolve 'clj-reload.core/reload) {:only :loaded}))))
          (let [after    ((requiring-resolve 'ol.vips/init!))
                metadata (requiring-resolve 'ol.vips/metadata)]
            (with-open [fresh   ((requiring-resolve 'ol.vips.operations/black) 8 6)
                        average ((requiring-resolve 'ol.vips/call) "avg" {:in held})]
              (let [observed {:reloaded?          (not (identical? image-handle-before
                                                                   @(requiring-resolve 'ol.vips.impl.api/image-handle)))
                              :same-state?        (identical? before after)
                              :same-lookup?       (identical? (:lookup before) (:lookup after))
                              :same-bindings?     (identical? (:bindings before) (:bindings after))
                              :same-image-class?  (identical? (class held) (class fresh))
                              :same-result-class? (identical? (class result) (class average))
                              :bridge-failure     (.get ^java.util.concurrent.atomic.AtomicReference
                                                   ((requiring-resolve 'ol.vips.impl.handles/stream-failure-ref) bridge))
                              :fresh-image        (metadata fresh)
                              :held-image         (metadata held)
                              :result-image       (metadata result)
                              :held-average       (:out average)
                              :stream-encoded?    (pos? (alength ^bytes ((requiring-resolve 'ol.vips/write-to-buffer)
                                                                         streamed ".png")))}]
                (dotimes [_ 2]
                  (.close ^java.lang.AutoCloseable held)
                  (.close ^java.lang.AutoCloseable result)
                  (.close ^java.lang.AutoCloseable streamed))
                (prn (assoc observed
                            :stream-close-count @close-count
                            :closed-images
                            (mapv (fn [image]
                                    (try
                                      (metadata image)
                                      :unexpected-open
                                      (catch clojure.lang.ExceptionInfo e
                                        (:type (ex-data e)))))
                                  [held result streamed])))))))))))

(defn- check-reload
  [mode extra-args]
  (let [{:keys [exit out err]} (apply shell/sh "clojure"
                                      (concat extra-args
                                              [(str "-J-Dol.vips.test.reload=" mode)
                                               "-M:dev" "-e" reload-probe]))
        metadata               {:width 8 :height 6 :bands 1 :has-alpha? false}]
    (is (zero? exit) (str out err))
    (when (zero? exit)
      (is (= {:reloaded?          (not= "none" mode)
              :same-state?        true
              :same-lookup?       true
              :same-bindings?     true
              :same-image-class?  true
              :same-result-class? true
              :bridge-failure     nil
              :fresh-image        metadata
              :held-image         metadata
              :result-image       metadata
              :held-average       0.0
              :stream-encoded?    true
              :stream-close-count 1
              :closed-images      (vec (repeat 3 :ol.vips/closed-image-handle))}
             (edn/read-string out))
          err))))

(deftest image-handles-survive-namespace-reload
  (doseq [mode ["none" "require" "require-all" "source" "clj-reload"]]
    (testing mode
      (check-reload mode []))))

(deftest aot-handles-survive-namespace-reload
  (let [classes (.toFile (Files/createTempDirectory "ol-vips-reload-aot-" (make-array FileAttribute 0)))
        path    (.getAbsolutePath classes)]
    (try
      (let [{:keys [exit out err]}
            (shell/sh "clojure" (str "-J-Dol.vips.test.aot-path=" path) "-M:dev" "-e"
                      (pr-str '(do
                                 (require 'ol.vips.impl.handles)
                                 (binding [*compile-path* (System/getProperty "ol.vips.test.aot-path")]
                                   (compile 'ol.vips.impl.handles)))))]
        (is (zero? exit) (str out err))
        (when (zero? exit)
          (doseq [mode ["require-all" "source"]]
            (testing mode
              (check-reload mode
                            ["-Sdeps" (pr-str {:paths [path "src"]})
                             (str "-J-Dol.vips.test.aot-path=" path)])))))
      (finally
        (doseq [file (reverse (file-seq classes))]
          (io/delete-file file))))))
