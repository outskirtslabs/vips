(ns animated-gif
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :refer [pprint]]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def input-path
  (fs/path "test" "fixtures" "cogs.gif"))

(def output-path
  (fs/path "examples" "cogs_rotated.gif"))

(defn -main [& _]
  (fs/create-dirs "examples")
  (with-open [image   (ops/gifload input-path {:n -1})
              cropped (v/extract-area-pages image 10 7 50 50)
              padded  (v/embed-pages cropped 8 8 70 70 {:extend     :background
                                                        :background [0 0 0 0]})
              turned  (v/rot-pages padded :d90)
              looped  (v/assoc-loop-count turned 2)]
    (v/write-to-file looped output-path)
    (pprint (select-keys (v/metadata looped)
                         [:width :height :pages :page-height :loop :delay]))
    (println (str "animated gif: " output-path))))

(-main)
