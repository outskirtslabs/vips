(ns compose-images
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def joined-output-path
  (fs/path "examples" "rabbit_fox_joined.jpg"))

(def grid-output-path
  (fs/path "examples" "rabbit_grid.jpg"))

(def rabbit-path
  (fs/path "dev" "rabbit.jpg"))

(def puppies-path
  (fs/path "test" "fixtures" "puppies.jpg"))

(defn -main [& _]
  (fs/create-dirs "examples")
  (with-open [rabbit-500  (ops/thumbnail rabbit-path 500)
              puppies-500 (ops/thumbnail puppies-path 500)
              joined      (ops/join rabbit-500 puppies-500 :horizontal {:shim 10})
              rabbit-400  (ops/thumbnail rabbit-path 400)
              puppies-400 (ops/thumbnail puppies-path 400)
              grid        (ops/arrayjoin [rabbit-400 puppies-400 puppies-400 rabbit-400]
                                         {:across 2
                                          :shim   10
                                          :halign :centre
                                          :valign :centre})]
    (v/write-to-file joined joined-output-path)
    (v/write-to-file grid grid-output-path)
    (println (str "joined: " joined-output-path))
    (println (str "grid: " grid-output-path))))

(-main)
