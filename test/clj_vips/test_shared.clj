(ns clj-vips.test-shared
  (:require
   [coffi.mem :as mem]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-vips.api :as api]))

(def ^:dynamic *vips-initialized* false)

(defn vips-fixture [f]
  (let [should-shutdown? (not *vips-initialized*)]
    (when-not *vips-initialized*
      (api/vips-init "clj-vips-test")
      (api/vips-leak-set 1)
      (api/init-gtypes!) ; Initialize GType constants for operation tests
      (alter-var-root #'*vips-initialized* (constantly true)))
    (f)
    (when should-shutdown?
      (api/vips-shutdown)
      (alter-var-root #'*vips-initialized* (constantly false)))))
