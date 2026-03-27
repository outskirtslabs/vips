(ns ol.vips-examples-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ol.vips :as v]))

(declare image)

(def output-root (fs/path "examples"))

(defn image-size [path]
  (v/with-image [image path]
    (select-keys (v/image-info image) [:width :height :has-alpha?])))

(defn run-example!
  [script-path]
  (shell/sh "clojure" "-M:dev" script-path))

(deftest runnable-examples
  (let [thumbnail-path (fs/path output-root "rabbit_thumbnail_400.jpg")
        chain-path     (fs/path output-root "rabbit_chain.jpg")
        joined-path    (fs/path output-root "rabbit_fox_joined.jpg")
        grid-path      (fs/path output-root "rabbit_grid.jpg")
        io-path        (fs/path output-root "rabbit_stream_bytes.png")
        metadata-path  (fs/path output-root "rabbit_metadata_copy.jpg")]
    (doseq [path [thumbnail-path chain-path joined-path grid-path io-path metadata-path]]
      (fs/delete-if-exists path))

    (testing "thumbnail example is runnable as a script"
      (let [{:keys [exit out err]} (run-example! "examples/create_thumbnail.clj")]
        (is (zero? exit) (str out err))
        (is (fs/exists? thumbnail-path))
        (is (= {:width 323 :height 400 :has-alpha? false}
               (image-size thumbnail-path)))))

    (testing "chain example is runnable as a script"
      (let [{:keys [exit out err]} (run-example! "examples/chain_transforms.clj")]
        (is (zero? exit) (str out err))
        (is (fs/exists? chain-path))
        (is (= {:width 400 :height 323 :has-alpha? false}
               (image-size chain-path)))))

    (testing "composition example is runnable as a script"
      (let [{:keys [exit out err]} (run-example! "examples/compose_images.clj")]
        (is (zero? exit) (str out err))
        (is (fs/exists? joined-path))
        (is (fs/exists? grid-path))
        (is (= {:width 808 :height 500 :has-alpha? false}
               (image-size joined-path)))
        (is (= {:width 656 :height 810 :has-alpha? false}
               (image-size grid-path)))))

    (testing "bytes and stream example is runnable as a script"
      (let [{:keys [exit out err]} (run-example! "examples/bytes_and_streams.clj")]
        (is (zero? exit) (str out err))
        (is (fs/exists? io-path))
        (is (= {:width 161 :height 200 :has-alpha? false}
               (image-size io-path)))))

    (testing "metadata example is runnable as a script"
      (let [{:keys [exit out err]} (run-example! "examples/metadata_roundtrip.clj")]
        (is (zero? exit) (str out err))
        (is (fs/exists? metadata-path))
        (is (str/includes? out "metadata ok"))
        (is (= {:width 2490 :height 3084 :has-alpha? false}
               (image-size metadata-path)))))))
