(ns ol.vips-test-common
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]))

(def fixture-path
  (str (fs/path "dev" "rabbit.jpg")))

(defonce runtime-state
  (v/init!))
