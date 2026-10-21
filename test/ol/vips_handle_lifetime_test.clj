(ns ol.vips-handle-lifetime-test
  (:require
   [babashka.ffi :as ffi]
   [clojure.test :refer [deftest is testing]]
   [ol.vips.impl.api :as runtime]
   [ol.vips.impl.handles :as handles]
   [ol.vips.impl.introspect :as introspect])
  (:import [java.lang AutoCloseable]))

(defn- new-matrix
  []
  (let [libraries (:lookup (runtime/ensure-initialized!))
        symbol    (if (seq libraries)
                    (some #(ffi/find-symbol % "vips_image_new_matrix") libraries)
                    (ffi/find-symbol "vips_image_new_matrix"))]
    ((ffi/cfn symbol [:int :int] :pointer) 3 2)))

(defn- with-tracked-object
  [create f]
  (with-open [arena (ffi/shared-arena)]
    (let [ptr       (create)
          finalized (atom 0)
          on-retain (atom nil)
          ref!      (runtime/bindings :g-object-ref)
          notify    (ffi/callback arena (fn [_ _] (swap! finalized inc))
                                  [:pointer :pointer] :void)]
      (assert (not (ffi/null? ptr)))
      ((runtime/bindings :g-object-weak-ref) ptr notify ffi/null)
      (with-open [^AutoCloseable handle (handles/->NativeHandle
                                         ptr []
                                         (fn [ptr]
                                           (let [retained (ref! ptr)]
                                             (when-let [f @on-retain] (f))
                                             retained))
                                         (runtime/bindings :g-object-unref))]
        (f {:handle handle :finalized finalized :on-retain on-retain})))))

(deftest borrow-survives-concurrent-close
  (with-tracked-object new-matrix
    (fn [{:keys [handle finalized]}]
      (let [closing (atom nil)]
        (is (= 3 (handles/with-pointer handle
                   (fn [ptr]
                     (reset! closing (future (.close ^AutoCloseable handle)))
                     (is (nil? (deref @closing 5000 ::timeout)))
                     (is (zero? @finalized))
                     ((runtime/bindings :image-get-width) ptr)))))
        (is (nil? (deref @closing 5000 ::timeout)))
        (is (= 1 @finalized))
        (.close ^AutoCloseable handle)
        (is (= 1 @finalized))))))

(deftest close-before-borrow-rejects-native-access
  (with-tracked-object new-matrix
    (fn [{:keys [handle finalized]}]
      (.close ^AutoCloseable handle)
      (is (= 1 @finalized))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed image handle"
                            (handles/with-pointer handle
                              (fn [_] (throw (AssertionError. "callback must not run"))))))
      (is (= 1 @finalized)))))

(deftest exception-releases-borrowed-reference
  (with-tracked-object new-matrix
    (fn [{:keys [handle finalized]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"borrow failed"
                            (handles/with-pointer handle
                              (fn [_]
                                (.close ^AutoCloseable handle)
                                (is (zero? @finalized))
                                (throw (ex-info "borrow failed" {}))))))
      (is (= 1 @finalized)))))

(deftest image-array-failure-releases-every-acquired-reference
  (doseq [failure [:closed :wrong-type]]
    (testing (name failure)
      (with-tracked-object new-matrix
        (fn [{first-image :handle first-finalized :finalized first-retain :on-retain}]
          (with-tracked-object (if (= :closed failure)
                                 new-matrix
                                 (runtime/bindings :target-new-to-memory))
            (fn [{later :handle later-finalized :finalized later-retain :on-retain}]
              (reset! first-retain #(.close ^AutoCloseable first-image))
              (if (= :closed failure)
                (.close ^AutoCloseable later)
                (reset! later-retain #(.close ^AutoCloseable later)))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                    (if (= :closed failure) #"closed image handle" #"VipsImage")
                                    (runtime/with-gvalue ((runtime/bindings :array-image-get-type))
                                      (fn [value]
                                        (#'introspect/encode-array-image
                                         (runtime/bindings) [first-image later] value)))))
              (is (= [1 1] [@first-finalized @later-finalized])))))))))

(deftest borrowed-metadata-is-copied-before-final-release
  (doseq [[kind value expected] [[:string "retained string" "retained string"]
                                 [:array-int [2 3 5] [2 3 5]]
                                 [:array-double [1.5 2.5] [1.5 2.5]]
                                 [:blob (byte-array [1 2 3]) [1 2 3]]]]
    (testing (name kind)
      (with-tracked-object new-matrix
        (fn [{:keys [handle finalized on-retain]}]
          (runtime/image-assoc-field! handle "lifetime-test" value {:type kind})
          (reset! on-retain #(.close ^AutoCloseable handle))
          (let [actual (runtime/image-field handle "lifetime-test")]
            (is (= expected (if (= :blob kind) (vec actual) actual))))
          (is (= 1 @finalized)))))))
