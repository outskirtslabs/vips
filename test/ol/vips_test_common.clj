(ns ol.vips-test-common
  (:require
   [babashka.fs :as fs]
   [ol.vips :as v]))

(def fixture-path
  (str (fs/path "dev" "rabbit.jpg")))

(def fixture-root
  (fs/path "test" "fixtures"))

(def puppies-path
  (str (fs/path fixture-root "puppies.jpg")))

(def alpha-band-path
  (str (fs/path fixture-root "alpha_band.png")))

(def gradient-path
  (str (fs/path fixture-root "gradient.png")))

(defonce runtime-state
  (v/init!))
