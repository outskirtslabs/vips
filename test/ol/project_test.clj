(ns ol.project-test
  (:require
   [ol.project]
   [clojure.test :refer [deftest is testing]]))

(deftest smoke
  (testing "the project namespace loads"
    (is (some? (find-ns 'ol.project)))))
