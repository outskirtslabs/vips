(ns ^:no-doc ol.vips.impl.introspect
  (:require
   [coffi.mem :as mem]
   [ol.vips.impl.api :as runtime]))

(set! *warn-on-reflection* true)

(defonce ^:private operation-cache* (atom nil))

(defn describe-operation
  [operation-name]
  (runtime/operation-info operation-name))

(defn- operation-nicknames
  []
  (or @operation-cache*
      (let [type-map-all (runtime/bindings :type-map-all)
            nicknames    (volatile! [])]
        (type-map-all (:operation (runtime/gtypes))
                      (fn [gtype _]
                        (when-let [nickname ((runtime/bindings :nickname-find) gtype)]
                          (vswap! nicknames conj nickname))
                        mem/null)
                      mem/null)
        (let [result (->> @nicknames distinct sort vec)]
          (reset! operation-cache* result)
          result))))

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
        pointer-size  (mem/size-of ::mem/pointer)
        pointer-align (mem/align-of ::mem/pointer)]
    (with-open [arena (mem/confined-arena)]
      (let [image-ptrs (mem/alloc (* (count images) pointer-size) pointer-align arena)]
        (doseq [[index image] (map-indexed vector images)]
          (mem/write-address image-ptrs
                             (* index pointer-size)
                             (runtime/pointer (runtime/image-handle image))))
        (let [boxed ((:array-image-new native) image-ptrs (count images))]
          (when (mem/null? boxed)
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
    (with-open [arena (mem/confined-arena)]
      (let [values (double-array (map double numbers))
            data   (mem/alloc (* (count numbers) (mem/size-of ::mem/double))
                              (mem/align-of ::mem/double)
                              arena)]
        (mem/write-doubles data (count numbers) values)
        (let [boxed ((:array-double-new native) data (count numbers))]
          (when (mem/null? boxed)
            (throw (ex-info "Failed to encode boxed double array"
                            {:kind       :boxed
                             :value-type "VipsArrayDouble"
                             :value      numbers
                             :error      ((:vips-error-buffer native))})))
          (try
            ((:g-value-set-boxed native) gvalue boxed)
            (finally
              ((:area-unref native) boxed))))))))

(defn- encode-boxed-value
  [native value-type value gvalue]
  (case value-type
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
    :object ((:g-value-set-object native) gvalue (runtime/pointer (runtime/image-handle value)))
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
    :object (let [ptr ((:g-value-get-object native) gvalue)]
              (when-not (mem/null? ptr)
                ((:g-object-ref native) ptr)
                (runtime/adopt-image ptr)))
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
  [operation-name opts]
  (runtime/ensure-initialized!)
  (let [operation (runtime/open-operation operation-name)
        open-op   (volatile! operation)]
    (try
      (let [args        (runtime/operation-arguments operation-name)
            arg-by-name (into {} (map (juxt :name identity) args))]
        (doseq [[k v] opts]
          (let [arg-name (runtime/require-name-string k "operation argument name")
                arg      (get arg-by-name arg-name)]
            (when-not arg
              (throw (ex-info "Unknown operation argument"
                              {:operation operation-name
                               :argument  arg-name})))
            (runtime/with-gvalue (:gtype arg)
              (fn [gvalue]
                (encode-value (runtime/bindings) arg v gvalue)
                ((runtime/bindings :g-object-set-property) operation arg-name gvalue)))))
        (let [built ((runtime/bindings :cache-operation-build) operation)]
          (when (mem/null? built)
            (throw (ex-info "Failed to build operation"
                            {:operation operation-name
                             :error     ((runtime/bindings :vips-error-buffer))})))
          ((runtime/bindings :g-object-unref) operation)
          (vreset! open-op mem/null)
          (try
            (runtime/operation-result
             (into {}
                   (for [arg   args
                         :when (:output? arg)]
                     [(keyword (:name arg))
                      (runtime/with-gvalue (:gtype arg)
                        (fn [gvalue]
                          ((runtime/bindings :g-object-get-property) built (:name arg) gvalue)
                          (decode-value (runtime/bindings) arg gvalue)))])))
            (finally
              ((runtime/bindings :object-unref-outputs) built)
              ((runtime/bindings :g-object-unref) built)))))
      (catch Throwable t
        (when-not (mem/null? @open-op)
          ((runtime/bindings :g-object-unref) @open-op))
        (throw t)))))
