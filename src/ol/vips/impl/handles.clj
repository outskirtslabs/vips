(ns ^:no-doc ol.vips.impl.handles
  (:require
   [ol.vips.impl.handles.protocols :refer [PointerBacked StreamFailureBacked]]))

(set! *warn-on-reflection* true)

(defn ->OperationResult
  [result-map]
  (let [closed? (atom false)]
    (proxy [clojure.lang.APersistentMap java.lang.AutoCloseable] []
      (iterator [] (.iterator ^Iterable result-map))
      (containsKey [key] (contains? result-map key))
      (entryAt [key] (find result-map key))
      (valAt
        ([key] (get result-map key))
        ([key not-found] (get result-map key not-found)))
      (assoc [key value] (assoc result-map key value))
      (without [key] (dissoc result-map key))
      (seq [] (seq result-map))
      (count [] (count result-map))
      (empty [] {})
      (close []
        (when (compare-and-set! closed? false true)
          (doseq [value (vals result-map)]
            (when (instance? java.lang.AutoCloseable value)
              (.close ^java.lang.AutoCloseable value))))))))

(defn ->ImageHandle
  [ptr keeper unref!]
  (let [closed? (atom false)]
    (reify
      PointerBacked
      (pointer [_]
        (if @closed?
          (throw (ex-info "Cannot use closed image handle"
                          {:type :ol.vips/closed-image-handle}))
          ptr))
      java.lang.AutoCloseable
      (close [_]
        (when (compare-and-set! closed? false true)
          (unref! ptr)
          (when keeper
            (.close ^java.lang.AutoCloseable keeper)))))))

(defn ->StreamBridge
  [ptr failure-ref unref!]
  (let [closed? (atom false)]
    (reify
      PointerBacked
      (pointer [_] ptr)
      StreamFailureBacked
      (stream-failure-ref [_] failure-ref)
      java.lang.AutoCloseable
      (close [_]
        (when (compare-and-set! closed? false true)
          (unref! ptr))))))

;; Reify class names vary with compilation order; register the actual close methods.
(when (= "buildtime" (System/getProperty "org.graalvm.nativeimage.imagecode"))
  (let [methods  (into-array java.lang.reflect.Executable
                             (map (fn [handle]
                                    (.getMethod (class handle) "close" (make-array Class 0)))
                                  [(->OperationResult {})
                                   (->ImageHandle nil nil identity)
                                   (->StreamBridge nil nil identity)]))
        register (.getMethod (Class/forName "org.graalvm.nativeimage.hosted.RuntimeReflection")
                             "register" (into-array Class [(class methods)]))]
    (.invoke register nil (object-array [methods]))))
