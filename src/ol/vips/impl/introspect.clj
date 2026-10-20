(ns ^:no-doc ol.vips.impl.introspect
  (:require
   [babashka.ffi :as ffi]
   [ol.vips.impl.api :as runtime]
   [ol.vips.impl.handles.protocols :as handle-protocols]))

(set! *warn-on-reflection* true)

(defonce ^:private operation-cache* (atom nil))

(defn describe-operation
  [operation-name]
  (runtime/operation-info operation-name))

(defn- operation-nicknames
  []
  (or @operation-cache*
      (with-open [arena (ffi/confined-arena)]
        (let [type-map-all (runtime/bindings :type-map-all)
              nicknames    (volatile! [])
              callback     (ffi/callback arena
                                         (fn [gtype _]
                                           (when-let [nickname ((runtime/bindings :nickname-find) gtype)]
                                             (vswap! nicknames conj nickname))
                                           ffi/null)
                                         [:long :pointer]
                                         :pointer)]
          (type-map-all (:operation (runtime/gtypes)) callback ffi/null)
          (let [result (->> @nicknames distinct sort vec)]
            (reset! operation-cache* result)
            result)))))

(defn list-operations
  []
  (runtime/ensure-initialized!)
  (operation-nicknames))

(defn describe-enum
  [enum-type-name]
  (runtime/describe-enum enum-type-name))

(defn encode-enum
  [enum-type-name value]
  (runtime/encode-enum enum-type-name value))

(defn decode-enum
  [enum-type-name value]
  (runtime/decode-enum enum-type-name value))

(defn- array-image?
  [value]
  (and (sequential? value)
       (every? (fn [image]
                 (try
                   (runtime/image-handle image)
                   true
                   (catch Throwable _
                     false)))
               value)))

(defn- encode-array-image
  [native images gvalue]
  (let [images        (vec images)
        pointer-size  (ffi/sizeof :pointer)
        pointer-align (ffi/alignof :pointer)]
    (with-open [arena (ffi/confined-arena)]
      (let [image-ptrs (ffi/alloc arena (* (count images) pointer-size) pointer-align)]
        (doseq [[index image] (map-indexed vector images)]
          (ffi/write image-ptrs :pointer (handle-protocols/pointer (runtime/image-handle image)) (* index pointer-size)))
        (let [boxed ((:array-image-new native) image-ptrs (count images))]
          (when (ffi/null? boxed)
            (throw (ex-info "Failed to encode boxed image array"
                            {:kind       :boxed
                             :value-type "VipsArrayImage"
                             :value      images
                             :error      ((:vips-error-buffer native))})))
          (try
            ((:g-value-set-boxed native) gvalue boxed)
            (finally
              ((:area-unref native) boxed))))))))

(defn- finite-numeric-seq?
  [value]
  (and (sequential? value)
       (every? (fn [item]
                 (try
                   (runtime/require-finite-number item "boxed double array value")
                   true
                   (catch Throwable _
                     false)))
               value)))

(defn- encode-array-double
  [native numbers gvalue]
  (let [numbers (mapv #(runtime/require-finite-number % "boxed double array value") numbers)]
    (with-open [arena (ffi/confined-arena)]
      (let [values (double-array (map double numbers))
            data   (ffi/alloc arena (* (count numbers) (ffi/sizeof :double)) (ffi/alignof :double))]
        (when (seq numbers)
          (ffi/write-array data :double values))
        (let [boxed ((:array-double-new native) data (count numbers))]
          (when (ffi/null? boxed)
            (throw (ex-info "Failed to encode boxed double array"
                            {:kind       :boxed
                             :value-type "VipsArrayDouble"
                             :value      numbers
                             :error      ((:vips-error-buffer native))})))
          (try
            ((:g-value-set-boxed native) gvalue boxed)
            (finally
              ((:area-unref native) boxed))))))))

(defrecord BlobInput [ptr active?])

(defn with-blob-input
  [ptr f]
  (when (ffi/null? ptr)
    (runtime/throw-vips-error (runtime/bindings) "Failed to allocate blob" {}))
  (let [blob (->BlobInput ptr (atom true))]
    (try (f blob)
         (finally
           (reset! (:active? blob) false)
           ((runtime/bindings :area-unref) ptr)))))

(defn with-copied-blob
  [value f]
  (let [data (runtime/->byte-array value "blob input")
        size (alength ^bytes data)]
    (with-open [arena (ffi/confined-arena)]
      (let [buffer (ffi/alloc arena (max 1 size) 1)]
        (ffi/write-array buffer :byte data)
        (with-blob-input ((runtime/bindings :blob-copy) buffer size) f)))))

(defn- encode-blob
  [native value gvalue]
  (if (instance? BlobInput value)
    (do
      (when-not @(:active? value)
        (throw (ex-info "Cannot use closed blob input" {})))
      ((:g-value-set-boxed native) gvalue (:ptr value)))
    (with-copied-blob value
      (fn [blob] ((:g-value-set-boxed native) gvalue (:ptr blob))))))

(defn blob-bytes
  [blob]
  (if (ffi/null? blob)
    (byte-array 0)
    (with-open [arena (ffi/confined-arena)]
      (let [size-ptr (ffi/alloc arena :size_t)
            data     ((runtime/bindings :blob-get) blob size-ptr)
            size     (ffi/read size-ptr :size_t)]
        (ffi/read-array (ffi/reinterpret data size) :byte size)))))

(defn- object-pointer
  [{:keys [value-type gtype]} value]
  (let [handle (if (= value-type "VipsImage") (runtime/image-handle value) value)]
    (when-not (satisfies? handle-protocols/PointerBacked handle)
      (throw (ex-info "Expected a typed native object handle" {:value-type value-type})))
    (let [ptr (handle-protocols/pointer handle)]
      (when (zero? ((runtime/bindings :g-type-check-instance-is-a) ptr gtype))
        (throw (ex-info "Native object has the wrong type" {:value-type value-type})))
      ptr)))

(defn- encode-boxed-value
  [native value-type value gvalue]
  (case value-type
    "VipsBlob" (encode-blob native value gvalue)
    "VipsArrayImage" (if (array-image? value)
                       (encode-array-image native value gvalue)
                       (throw (ex-info "Expected a sequential collection of image handles"
                                       {:kind       :boxed
                                        :value-type value-type
                                        :value      value})))
    "VipsArrayDouble" (if (finite-numeric-seq? value)
                        (encode-array-double native value gvalue)
                        (throw (ex-info "Expected a sequential collection of finite numbers"
                                        {:kind       :boxed
                                         :value-type value-type
                                         :value      value})))
    (throw (ex-info "Unsupported operation argument type"
                    {:kind       :boxed
                     :value-type value-type
                     :value      value}))))

(defn- encode-value
  [native {:keys [kind value-type name minimum maximum] :as arg} value gvalue]
  (case kind
    :object ((:g-value-set-object native) gvalue (object-pointer arg value))
    :boxed (encode-boxed-value native value-type value gvalue)
    :string ((:g-value-set-string native)
             gvalue
             (runtime/require-java-string value (str "Operation argument `" name "`")))
    :boolean ((:g-value-set-boolean native)
              gvalue
              (if (runtime/require-boolean value (str "Operation argument `" name "`")) 1 0))
    :int (let [label (str "Operation argument `" name "`")
               value (runtime/require-int32 value label)
               value (if (and (some? minimum) (some? maximum))
                       (runtime/require-param-spec-int-range arg value)
                       value)]
           ((:g-value-set-int native) gvalue (int value)))
    :uint ((:g-value-set-uint native)
           gvalue
           (unchecked-int (runtime/require-uint32 value (str "Operation argument `" name "`"))))
    :long ((:g-value-set-long native) gvalue (long (runtime/require-int64 value (str "Operation argument `" name "`"))))
    :int64 ((:g-value-set-int64 native) gvalue (long (runtime/require-int64 value (str "Operation argument `" name "`"))))
    :uint64 ((:g-value-set-uint64 native)
             gvalue
             (unchecked-long (runtime/require-uint64 value (str "Operation argument `" name "`"))))
    :double ((:g-value-set-double native) gvalue (double (runtime/require-finite-number value (str "Operation argument `" name "`"))))
    :enum ((:g-value-set-enum native)
           gvalue
           (int (runtime/require-int32 (runtime/encode-enum value-type value)
                                       (str "Operation argument `" name "`"))))
    :flags ((:g-value-set-flags native)
            gvalue
            (unchecked-int (runtime/encode-flags value-type value)))
    (throw (ex-info "Unsupported operation argument type"
                    {:kind       kind
                     :value-type value-type
                     :value      value}))))

(defn- decode-value
  [native {:keys [kind value-type gtype]} gvalue]
  (case kind
    :object (if (= value-type "VipsImage")
              (let [ptr ((:g-value-get-object native) gvalue)]
                (when-not (ffi/null? ptr)
                  ((:g-object-ref native) ptr)
                  (runtime/adopt-image ptr)))
              (throw (ex-info "Unsupported object output" {:value-type value-type})))
    :boxed (if (= value-type "VipsBlob")
             (blob-bytes ((:g-value-get-boxed native) gvalue))
             (throw (ex-info "Unsupported boxed output" {:value-type value-type})))
    :string ((:g-value-get-string native) gvalue)
    :boolean (not (zero? ((:g-value-get-boolean native) gvalue)))
    :int ((:g-value-get-int native) gvalue)
    :uint ((:g-value-get-uint native) gvalue)
    :long ((:g-value-get-int64 native) gvalue)
    :int64 ((:g-value-get-int64 native) gvalue)
    :uint64 ((:g-value-get-uint64 native) gvalue)
    :double ((:g-value-get-double native) gvalue)
    :enum (runtime/decode-enum value-type ((:g-value-get-enum native) gvalue))
    :flags ((:g-value-get-flags native) gvalue)
    (throw (ex-info "Unsupported operation output type"
                    {:kind       kind
                     :value-type value-type
                     :gtype      gtype}))))

(defn call-operation
  ([operation-name opts]
   (call-operation operation-name opts nil))
  ([operation-name opts option-string]
   (runtime/ensure-initialized!)
   (let [failure-refs (vec (mapcat runtime/native-failure-refs (vals opts)))
         operation    (runtime/open-operation operation-name)
         open-op      (volatile! operation)
         outputs      (volatile! {})]
     (try
       (let [args        (runtime/operation-arguments operation-name)
             arg-by-name (into {} (map (juxt :name identity) args))]
         (when (seq option-string)
           (when-not (zero? ((runtime/bindings :object-set-from-string) operation option-string))
             (runtime/throw-vips-error (runtime/bindings) "Failed to parse operation options"
                                       {:operation operation-name})))
         (doseq [[k v] opts]
           (let [arg-name (runtime/require-name-string k "operation argument name")
                 arg      (get arg-by-name arg-name)]
             (when-not arg
               (throw (ex-info "Unknown operation argument"
                               {:operation operation-name :argument arg-name})))
             (runtime/with-gvalue (:gtype arg)
               (fn [gvalue]
                 (encode-value (runtime/bindings) arg v gvalue)
                 ((runtime/bindings :g-object-set-property) operation arg-name gvalue)))))
         (let [built ((runtime/bindings :cache-operation-build) operation)]
           (when (ffi/null? built)
             (runtime/throw-vips-error (runtime/bindings) "Failed to build operation"
                                       {:operation operation-name}))
           ((runtime/bindings :g-object-unref) operation)
           (vreset! open-op ffi/null)
           (try
             (doseq [arg args :when (:output? arg)]
               (let [value (runtime/with-gvalue (:gtype arg)
                             (fn [gvalue]
                               ((runtime/bindings :g-object-get-property) built (:name arg) gvalue)
                               (decode-value (runtime/bindings) arg gvalue)))]
                 (vswap! outputs assoc (keyword (:name arg)) value)
                 (runtime/retain-failure-refs! value failure-refs)))
             (runtime/operation-result @outputs)
             (finally
               ((runtime/bindings :object-unref-outputs) built)
               ((runtime/bindings :g-object-unref) built)))))
       (catch Throwable t
         (doseq [value (vals @outputs)]
           (when (satisfies? handle-protocols/PointerBacked value)
             (runtime/close-quietly value)))
         (when-not (ffi/null? @open-op)
           ((runtime/bindings :object-unref-outputs) @open-op)
           ((runtime/bindings :g-object-unref) @open-op))
         (runtime/throw-with-stream-failure t failure-refs))))))
