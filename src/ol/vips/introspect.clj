(ns ol.vips.introspect
  (:require
   [clojure.string :as str]
   [coffi.layout :as layout]
   [coffi.mem :as mem]
   [ol.vips.runtime :as runtime]))

(set! *warn-on-reflection* true)

(def ^:private vips-argument-required 1)
(def ^:private vips-argument-input 16)
(def ^:private vips-argument-output 32)

(mem/defalias ::g-type-instance
  (layout/with-c-layout
    [::mem/struct
     [[:g-class ::mem/pointer]]]))

(mem/defalias ::g-param-spec-head
  (layout/with-c-layout
    [::mem/struct
     [[:g-type-instance ::g-type-instance]
      [:name ::mem/pointer]
      [:flags ::mem/int]
      [:value-type ::runtime/g-type]
      [:owner-type ::runtime/g-type]]]))

(mem/defalias ::g-enum-value
  (layout/with-c-layout
    [::mem/struct
     [[:value ::mem/int]
      [:value-name ::mem/c-string]
      [:value-nick ::mem/c-string]]]))

(mem/defalias ::g-enum-class
  (layout/with-c-layout
    [::mem/struct
     [[:g-type-class ::mem/pointer]
      [:minimum ::mem/int]
      [:maximum ::mem/int]
      [:n-values ::mem/int]
      [:values ::mem/pointer]]]))

(defonce ^:private enum-cache* (atom nil))
(defonce ^:private operation-cache* (atom nil))

(defn- bit-set?
  [value flag]
  (not (zero? (bit-and value flag))))

(defn- enum-keyword
  [value-nick value-name]
  (-> (or value-nick value-name)
      str/lower-case
      (str/replace #"^vips[_-]?" "")
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")
      keyword))

(defn- classify-gtype
  [gtype]
  (let [gtypes      (runtime/gtypes)
        fundamental (runtime/type-fundamental gtype)]
    (cond
      (= gtype (:string gtypes)) :string
      (= gtype (:boolean gtypes)) :boolean
      (= gtype (:int gtypes)) :int
      (= gtype (:uint gtypes)) :uint
      (= gtype (:long gtypes)) :long
      (= gtype (:int64 gtypes)) :int64
      (= gtype (:uint64 gtypes)) :uint64
      (= gtype (:double gtypes)) :double
      (= fundamental (:enum gtypes)) :enum
      (= fundamental (:flags gtypes)) :flags
      (= fundamental (:boxed gtypes)) :boxed
      (= fundamental (:object gtypes)) :object
      :else :unknown)))

(defn- deserialize-struct
  [ptr type]
  (mem/deserialize (mem/reinterpret ptr (mem/size-of type)) type))

(defn- describe-argument
  [native op name pspec-ptr]
  (let [flags      ((:object-get-arg-flags native) op name)
        priority   ((:object-get-arg-priority native) op name)
        value-type (:value-type (deserialize-struct pspec-ptr ::g-param-spec-head))]
    {:name       name
     :blurb      ((:param-spec-get-blurb native) pspec-ptr)
     :flags      flags
     :gtype      value-type
     :kind       (classify-gtype value-type)
     :value-type (runtime/type-name value-type)
     :priority   priority
     :input?     (bit-set? flags vips-argument-input)
     :output?    (bit-set? flags vips-argument-output)
     :required?  (bit-set? flags vips-argument-required)}))

(defn- operation-handle
  [operation-name]
  (let [op ((runtime/bindings :operation-new) operation-name)]
    (when (mem/null? op)
      (throw (ex-info "Unknown libvips operation"
                      {:operation operation-name})))
    op))

(defn describe-operation
  [operation-name]
  (runtime/ensure-initialized!)
  (let [argument-map (runtime/bindings :argument-map)
        op           (operation-handle operation-name)]
    (try
      (let [args (volatile! [])]
        (argument-map op
                      (fn [_object pspec-ptr _arg-class-ptr _instance _user-data _extra]
                        (let [name ((runtime/bindings :param-spec-get-name) pspec-ptr)]
                          (vswap! args conj (describe-argument (runtime/bindings) op name pspec-ptr)))
                        mem/null)
                      mem/null
                      mem/null)
        {:name        operation-name
         :description ((runtime/bindings :object-get-description) op)
         :args        (->> @args
                           (sort-by (juxt (complement :required?) :priority))
                           vec)})
      (finally
        ((runtime/bindings :g-object-unref) op)))))

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

(defn- warm-operation-types!
  []
  (doseq [nickname (operation-nicknames)]
    (let [op ((runtime/bindings :operation-new) nickname)]
      (when-not (mem/null? op)
        ((runtime/bindings :g-object-unref) op)))))

(defn list-operations
  []
  (runtime/ensure-initialized!)
  (operation-nicknames))

(defn- discover-enums
  []
  (runtime/ensure-initialized!)
  (warm-operation-types!)
  (let [enum-type (:enum (runtime/gtypes))]
    (with-open [arena (mem/confined-arena)]
      (let [count-ptr (mem/alloc-instance ::mem/int arena)
            children  ((runtime/bindings :g-type-children) enum-type count-ptr)
            count     (mem/read-int count-ptr)
            slot-size (mem/size-of ::runtime/g-type)
            children* (mem/reinterpret children (* count slot-size))]
        (into {}
              (for [index (range count)
                    :let  [offset     (* index slot-size)
                           child-type (mem/read-long (mem/slice children* offset slot-size))
                           type-name   (runtime/type-name child-type)
                           class-ptr   ((runtime/bindings :g-type-class-ref) child-type)]
                    :when (and type-name (not (mem/null? class-ptr)))]
                (try
                  (let [class*     (mem/reinterpret class-ptr (mem/size-of ::g-enum-class))
                        enum-class (mem/deserialize class* ::g-enum-class)
                        n-values   (:n-values enum-class)
                        value-size (mem/size-of ::g-enum-value)
                        values*    (mem/reinterpret (:values enum-class) (* n-values value-size))
                        entries    (into {}
                                         (for [i     (range n-values)
                                               :let  [entry (mem/deserialize
                                                             (mem/slice values* (* i value-size) value-size)
                                                             ::g-enum-value)
                                                      keyword (enum-keyword (:value-nick entry)
                                                                            (:value-name entry))]
                                               :when (not= keyword :last)]
                                           [keyword (:value entry)]))]
                    [type-name {:type-name      type-name
                                :keyword->value entries
                                :value->keyword (into {} (map (fn [[k v]] [v k]) entries))}])
                  (finally
                    ((runtime/bindings :g-type-class-unref) class-ptr)))))))))

(defn- enum-registry
  []
  (or @enum-cache*
      (let [registry (discover-enums)]
        (reset! enum-cache* registry)
        registry)))

(defn describe-enum
  [enum-type-name]
  (or (get (enum-registry) enum-type-name)
      (throw (ex-info "Unknown enum type"
                      {:enum-type enum-type-name}))))

(defn encode-enum
  [enum-type-name value]
  (if (integer? value)
    value
    (or (get-in (describe-enum enum-type-name) [:keyword->value value])
        (throw (ex-info "Unknown enum value"
                        {:enum-type enum-type-name
                         :value     value})))))

(defn decode-enum
  [enum-type-name value]
  (or (get-in (describe-enum enum-type-name) [:value->keyword value])
      (throw (ex-info "Unknown enum integer"
                      {:enum-type enum-type-name
                       :value     value}))))

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

(defn- numeric-seq?
  [value]
  (and (sequential? value)
       (every? number? value)))

(defn- encode-array-double
  [native numbers gvalue]
  (let [numbers (vec numbers)]
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
    "VipsArrayDouble" (if (numeric-seq? value)
                        (encode-array-double native value gvalue)
                        (throw (ex-info "Expected a sequential collection of numbers"
                                        {:kind       :boxed
                                         :value-type value-type
                                         :value      value})))
    (throw (ex-info "Unsupported operation argument type"
                    {:kind       :boxed
                     :value-type value-type
                     :value      value}))))

(defn- encode-value
  [native {:keys [kind value-type]} value gvalue]
  (case kind
    :object ((:g-value-set-object native) gvalue (runtime/pointer (runtime/image-handle value)))
    :boxed (encode-boxed-value native value-type value gvalue)
    :string ((:g-value-set-string native) gvalue (str value))
    :boolean ((:g-value-set-boolean native) gvalue (if value 1 0))
    :int ((:g-value-set-int native) gvalue (int value))
    :uint ((:g-value-set-uint native) gvalue (int value))
    :long ((:g-value-set-long native) gvalue (long value))
    :int64 ((:g-value-set-int64 native) gvalue (long value))
    :uint64 ((:g-value-set-uint64 native) gvalue (long value))
    :double ((:g-value-set-double native) gvalue (double value))
    :enum ((:g-value-set-enum native) gvalue (int (encode-enum value-type value)))
    :flags ((:g-value-set-flags native) gvalue (int value))
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
    :enum (decode-enum value-type ((:g-value-get-enum native) gvalue))
    :flags ((:g-value-get-flags native) gvalue)
    (throw (ex-info "Unsupported operation output type"
                    {:kind       kind
                     :value-type value-type
                     :gtype      gtype}))))

(defn call-operation
  [operation-name opts]
  (runtime/ensure-initialized!)
  (let [operation (operation-handle operation-name)
        open-op   (volatile! operation)]
    (try
      (let [{:keys [args]} (describe-operation operation-name)
            arg-by-name    (into {} (map (juxt :name identity) args))]
        (doseq [[k v] opts]
          (let [arg-name (name k)
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
