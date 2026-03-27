(ns poc1
  (:require
   [babashka.fs :as fs])
  (:import
   (app.photofox.vipsffm VImage Vips VipsOption VipsOption$Boolean VipsRunnable)
   (java.util Optional)
   (java.util.concurrent.atomic AtomicReference)))

(def project-root
  (fs/absolutize "."))

(def input-path
  (str (fs/path project-root "dev" "rabbit.jpg")))

(def output-path
  (str (fs/path project-root "dev" "rabbit_thumbnail_400.jpg")))

(defn auto-rotate-option []
  (VipsOption$Boolean. "auto-rotate"
                       (AtomicReference. (Optional/of java.lang.Boolean/TRUE))))

(defn -main []
  (Vips/run
   (reify VipsRunnable
     (run [_ arena]
       (let [thumbnail (VImage/thumbnail arena input-path 400
                                         (into-array VipsOption [(auto-rotate-option)]))
             width (.getWidth thumbnail)
             height (.getHeight thumbnail)]
         (println (format "thumbnail image size: %d x %d" width height))
         (.writeToFile thumbnail output-path (into-array VipsOption []))
         (println (str "wrote " output-path))))))
  (Vips/shutdown))

(-main)
