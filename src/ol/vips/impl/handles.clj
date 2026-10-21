(ns ^:no-doc ol.vips.impl.handles
  (:require
   [ol.vips.impl.handles.protocols :refer [PointerBacked retain-pointer]]))

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

(defn ->NativeHandle
  [ptr failure-refs ref! unref!]
  (let [pointer (atom ptr)
        state   {:pointer pointer :failure-refs failure-refs :unref! unref!}]
    (reify
      clojure.lang.IDeref
      (deref [_] state)
      PointerBacked
      (retain-pointer [_]
        (locking pointer
          (ref! (or @pointer
                    (throw (ex-info "Cannot use closed image handle"
                                    {:type :ol.vips/closed-image-handle}))))))
      java.lang.AutoCloseable
      (close [_]
        (let [[ptr _] (locking pointer (reset-vals! pointer nil))]
          ;; Native finalization can call user code; never run it under the lock.
          (when ptr (unref! ptr)))))))

(defn with-pointer
  "Calls `f` with a retained pointer and releases it on return or exception.
  The pointer and any borrowed native data must not escape `f`."
  [handle f]
  (let [unref! (:unref! @handle)
        ptr    (retain-pointer handle)]
    (try (f ptr)
         (finally (unref! ptr)))))

;; Reify class names vary with compilation order; register the actual close methods.
(when (= "buildtime" (System/getProperty "org.graalvm.nativeimage.imagecode"))
  (let [methods  (into-array java.lang.reflect.Executable
                             (map (fn [handle]
                                    (.getMethod (class handle) "close" (make-array Class 0)))
                                  [(->OperationResult {})
                                   (->NativeHandle nil [] identity identity)]))
        register (.getMethod (Class/forName "org.graalvm.nativeimage.hosted.RuntimeReflection")
                             "register" (into-array Class [(class methods)]))]
    (.invoke register nil (object-array [methods]))))
