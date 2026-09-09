(ns ^:no-doc ol.vips.impl.api
  (:require
   [babashka.ffi :as ffi]
   [clojure.string :as str]
   [ol.vips.impl.loader :as loader])
  (:import
   [java.io File InputStream OutputStream]
   [java.lang.foreign Arena]
   [java.nio.file Path]
   [java.util.concurrent.atomic AtomicBoolean AtomicReference]))

(set! *warn-on-reflection* true)

(defn require-boolean
  [value label]
  (if (boolean? value)
    value
    (throw (ex-info (str label " must be a boolean")
                    {:label    label
                     :expected [:boolean]
                     :value    value}))))

(defn require-number
  [value label]
  (if (number? value)
    value
    (throw (ex-info (str label " must be a number")
                    {:label    label
                     :expected [:number]
                     :value    value}))))

(defn require-finite-number
  [value label]
  (let [value (require-number value label)]
    (if (Double/isFinite (double value))
      value
      (throw (ex-info (str label " must be a finite number")
                      {:label    label
                       :expected [:finite-number]
                       :value    value})))))

(defn require-integer
  [value label]
  (if (integer? value)
    value
    (throw (ex-info (str label " must be an integer")
                    {:label    label
                     :expected [:integer]
                     :value    value}))))

(defn require-integer-range
  [value label minimum maximum range-description]
  (let [value (require-integer value label)]
    (if (<= minimum value maximum)
      value
      (throw (ex-info (str label " must fit in " range-description)
                      {:label    label
                       :expected [:integer :range]
                       :range    range-description
                       :minimum  minimum
                       :maximum  maximum
                       :value    value})))))

;; These bounds match GLib fixed-width integer types like guint/guint64,
;; not platform-sized C integer aliases such as glong.
(def ^:private uint32-max
  4294967295N)

(def ^:private uint64-max
  18446744073709551615N)

(defn require-int32
  [value label]
  (require-integer-range value label Integer/MIN_VALUE Integer/MAX_VALUE "a 32-bit signed integer"))

(defn require-uint32
  [value label]
  (require-integer-range value label 0 uint32-max "a 32-bit unsigned integer"))

(defn require-int64
  [value label]
  (require-integer-range value label Long/MIN_VALUE Long/MAX_VALUE "a 64-bit signed integer"))

(defn require-uint64
  [value label]
  (require-integer-range value label 0 uint64-max "a 64-bit unsigned integer"))

(defn coerce-java-string
  [value]
  (cond
    (string? value)
    value

    (instance? Path value)
    (str value)

    (instance? File value)
    (.getPath ^File value)

    (instance? CharSequence value)
    (str value)

    :else
    nil))

(defn require-java-string
  [value label]
  (or (coerce-java-string value)
      (throw (ex-info (str label " must be a string, java.nio.file.Path, java.io.File, or CharSequence")
                      {:label    label
                       :expected [:string :path :file :char-sequence]
                       :value    value}))))

(defn coerce-name-string
  [value]
  (cond
    (string? value) value
    (instance? clojure.lang.Named value) (name value)
    :else nil))

(defn require-name-string
  [value label]
  (or (coerce-name-string value)
      (throw (ex-info (str label " must be a string, keyword, or symbol")
                      {:label    label
                       :expected [:string :keyword :symbol]
                       :value    value}))))

(def g-value [:struct
              [[:g-type :long]
               [:data [:array :long 2]]]])

(def g-type-instance [:struct
                      [[:g-class :pointer]]])

(def g-param-spec [:struct
                   [[:g-type-instance g-type-instance]
                    [:name :pointer]
                    [:flags :int]
                    [:value-type :long]
                    [:owner-type :long]
                    [:nick :pointer]
                    [:blurb :pointer]
                    [:qdata :pointer]
                    [:ref-count :int]
                    [:param-id :int]]])

(def g-param-spec-int [:struct
                       [[:parent-instance g-param-spec]
                        [:minimum :int]
                        [:maximum :int]
                        [:default-value :int]]])

(def g-enum-value [:struct
                   [[:value :int]
                    [:value-name :string]
                    [:value-nick :string]]])

(def g-enum-class [:struct
                   [[:g-type-class :pointer]
                    [:minimum :int]
                    [:maximum :int]
                    [:n-values :int]
                    [:values :pointer]]])

(def g-flags-value [:struct
                    [[:value :int]
                     [:value-name :string]
                     [:value-nick :string]]])

(def g-flags-class [:struct
                    [[:g-type-class :pointer]
                     [:mask :int]
                     [:n-values :int]
                     [:values :pointer]]])

(def ^:private source-read-callback-type
  [[:pointer :pointer :long :pointer] :long])

(def ^:private target-write-callback-type
  [[:pointer :pointer :long :pointer] :long])

(def ^:private target-end-callback-type
  [[:pointer :pointer] :int])

(def ^:private native-symbol-specs
  {:g-free                         ["g_free" [:pointer] :void]
   :g-strfreev                     ["g_strfreev" [:pointer] :void]
   :g-signal-connect-data          ["g_signal_connect_data"
                                    [:pointer :string :pointer :pointer :pointer :int]
                                    :long]
   :g-object-ref                   ["g_object_ref" [:pointer] :pointer]
   :g-object-unref                 ["g_object_unref" [:pointer] :void]
   :g-object-get-property          ["g_object_get_property"
                                    [:pointer :string :pointer]
                                    :void]
   :g-object-set-property          ["g_object_set_property"
                                    [:pointer :string :pointer]
                                    :void]
   :g-type-children                ["g_type_children" [:long :pointer] :pointer]
   :g-type-class-ref               ["g_type_class_ref" [:long] :pointer]
   :g-type-class-unref             ["g_type_class_unref" [:pointer] :void]
   :g-type-fundamental             ["g_type_fundamental" [:long] :long]
   :g-type-from-name               ["g_type_from_name" [:string] :long]
   :g-type-name                    ["g_type_name" [:long] :string]
   :param-spec-get-blurb           ["g_param_spec_get_blurb" [:pointer] :string]
   :param-spec-get-name            ["g_param_spec_get_name" [:pointer] :string]
   :nickname-find                  ["vips_nickname_find" [:long] :string]
   :argument-map                   ["vips_argument_map"
                                    [:pointer
                                     :pointer
                                     :pointer
                                     :pointer]
                                    :pointer]
   :type-map-all                   ["vips_type_map_all"
                                    [:long
                                     :pointer
                                     :pointer]
                                    :pointer]
   :g-value-get-boolean            ["g_value_get_boolean" [:pointer] :int]
   :g-value-get-double             ["g_value_get_double" [:pointer] :double]
   :g-value-get-enum               ["g_value_get_enum" [:pointer] :int]
   :g-value-get-flags              ["g_value_get_flags" [:pointer] :int]
   :g-value-get-int                ["g_value_get_int" [:pointer] :int]
   :g-value-get-int64              ["g_value_get_int64" [:pointer] :long]
   :g-value-get-object             ["g_value_get_object" [:pointer] :pointer]
   :g-value-get-string             ["g_value_get_string" [:pointer] :string]
   :g-value-get-uint               ["g_value_get_uint" [:pointer] :int]
   :g-value-get-uint64             ["g_value_get_uint64" [:pointer] :long]
   :g-value-init                   ["g_value_init" [:pointer :long] :pointer]
   :g-value-set-boolean            ["g_value_set_boolean" [:pointer :int] :void]
   :g-value-set-boxed              ["g_value_set_boxed" [:pointer :pointer] :void]
   :g-value-set-double             ["g_value_set_double" [:pointer :double] :void]
   :g-value-set-enum               ["g_value_set_enum" [:pointer :int] :void]
   :g-value-set-flags              ["g_value_set_flags" [:pointer :int] :void]
   :g-value-set-int                ["g_value_set_int" [:pointer :int] :void]
   :g-value-set-int64              ["g_value_set_int64" [:pointer :long] :void]
   :g-value-set-long               ["g_value_set_long" [:pointer :long] :void]
   :g-value-set-object             ["g_value_set_object" [:pointer :pointer] :void]
   :g-value-set-string             ["g_value_set_string" [:pointer :string] :void]
   :g-value-set-uint               ["g_value_set_uint" [:pointer :int] :void]
   :g-value-set-uint64             ["g_value_set_uint64" [:pointer :long] :void]
   :g-value-unset                  ["g_value_unset" [:pointer] :void]
   :image-get-height               ["vips_image_get_height" [:pointer] :int]
   :image-get-bands                ["vips_image_get_bands" [:pointer] :int]
   :image-get                      ["vips_image_get" [:pointer :string :pointer] :int]
   :image-get-array-double         ["vips_image_get_array_double"
                                    [:pointer :string :pointer :pointer]
                                    :int]
   :image-get-array-int            ["vips_image_get_array_int"
                                    [:pointer :string :pointer :pointer]
                                    :int]
   :image-get-as-string            ["vips_image_get_as_string"
                                    [:pointer :string :pointer]
                                    :int]
   :image-get-blob                 ["vips_image_get_blob"
                                    [:pointer :string :pointer :pointer]
                                    :int]
   :image-get-double               ["vips_image_get_double"
                                    [:pointer :string :pointer]
                                    :int]
   :image-get-fields               ["vips_image_get_fields" [:pointer] :pointer]
   :image-get-int                  ["vips_image_get_int"
                                    [:pointer :string :pointer]
                                    :int]
   :image-get-string               ["vips_image_get_string"
                                    [:pointer :string :pointer]
                                    :int]
   :image-get-type                 ["vips_image_get_type" [] :long]
   :image-get-typeof               ["vips_image_get_typeof" [:pointer :string] :long]
   :image-get-width                ["vips_image_get_width" [:pointer] :int]
   :image-has-alpha                ["vips_image_hasalpha" [:pointer] :int]
   :image-new-from-buffer          ["vips_image_new_from_buffer"
                                    [:pointer :size_t :string :& :pointer]
                                    :pointer]
   :image-new-from-file            ["vips_image_new_from_file"
                                    [:string :& :pointer]
                                    :pointer]
   :image-new-from-source          ["vips_image_new_from_source"
                                    [:pointer :string :& :pointer]
                                    :pointer]
   :foreign-find-load              ["vips_foreign_find_load" [:string] :string]
   :foreign-find-load-buffer       ["vips_foreign_find_load_buffer" [:pointer :size_t] :string]
   :foreign-find-load-source       ["vips_foreign_find_load_source" [:pointer] :string]
   :foreign-find-save              ["vips_foreign_find_save" [:string] :string]
   :foreign-find-save-buffer       ["vips_foreign_find_save_buffer" [:string] :string]
   :foreign-find-save-target       ["vips_foreign_find_save_target" [:string] :string]
   :image-copy-memory              ["vips_image_copy_memory" [:pointer] :pointer]
   :image-write-to-buffer          ["vips_image_write_to_buffer"
                                    [:pointer :string :pointer :pointer :& :pointer]
                                    :int]
   :image-write-to-file            ["vips_image_write_to_file"
                                    [:pointer :string :& :pointer]
                                    :int]
   :image-remove                   ["vips_image_remove" [:pointer :string] :int]
   :image-set                      ["vips_image_set" [:pointer :string :pointer] :void]
   :image-set-array-double         ["vips_image_set_array_double"
                                    [:pointer :string :pointer :int]
                                    :void]
   :image-set-array-int            ["vips_image_set_array_int"
                                    [:pointer :string :pointer :int]
                                    :void]
   :image-set-blob-copy            ["vips_image_set_blob_copy"
                                    [:pointer :string :pointer :size_t]
                                    :void]
   :image-set-double               ["vips_image_set_double" [:pointer :string :double] :void]
   :image-set-int                  ["vips_image_set_int" [:pointer :string :int] :void]
   :image-set-string               ["vips_image_set_string" [:pointer :string :string] :void]
   :image-write-to-target          ["vips_image_write_to_target"
                                    [:pointer :string :pointer :& :pointer]
                                    :int]
   :operation-get-type             ["vips_operation_get_type" [] :long]
   :operation-new                  ["vips_operation_new" [:string] :pointer]
   :array-image-get-type           ["vips_array_image_get_type" [] :long]
   :array-double-get-type          ["vips_array_double_get_type" [] :long]
   :array-image-new                ["vips_array_image_new" [:pointer :int] :pointer]
   :array-double-new               ["vips_array_double_new" [:pointer :int] :pointer]
   :area-unref                     ["vips_area_unref" [:pointer] :void]
   :object-get-description         ["vips_object_get_description" [:pointer] :string]
   :object-get-arg-flags           ["vips_object_get_argument_flags" [:pointer :string] :int]
   :object-get-arg-priority        ["vips_object_get_argument_priority" [:pointer :string] :int]
   :object-unref-outputs           ["vips_object_unref_outputs" [:pointer] :void]
   :cache-operation-build          ["vips_cache_operation_build" [:pointer] :pointer]
   :vips-cache-set-max             ["vips_cache_set_max" [:int] :void]
   :vips-cache-set-max-mem         ["vips_cache_set_max_mem" [:size_t] :void]
   :vips-cache-get-max             ["vips_cache_get_max" [] :int]
   :vips-cache-get-size            ["vips_cache_get_size" [] :int]
   :vips-cache-get-max-mem         ["vips_cache_get_max_mem" [] :size_t]
   :vips-cache-get-max-files       ["vips_cache_get_max_files" [] :int]
   :vips-cache-set-max-files       ["vips_cache_set_max_files" [:int] :void]
   :vips-tracked-get-mem           ["vips_tracked_get_mem" [] :size_t]
   :vips-tracked-get-mem-highwater ["vips_tracked_get_mem_highwater" [] :size_t]
   :vips-tracked-get-allocs        ["vips_tracked_get_allocs" [] :int]
   :vips-tracked-get-files         ["vips_tracked_get_files" [] :int]
   :source-custom-new              ["vips_source_custom_new" [] :pointer]
   :target-custom-new              ["vips_target_custom_new" [] :pointer]
   :vips-error-buffer              ["vips_error_buffer" [] :string]
   :vips-error-clear               ["vips_error_clear" [] :void]
   :vips-operation-block-set       ["vips_operation_block_set" [:string :int] :void]
   :vips-block-untrusted-set       ["vips_block_untrusted_set" [:int] :void]
   :vips-init                      ["vips_init" [:string] :int]
   :vips-shutdown                  ["vips_shutdown" [] :void]
   :vips-version                   ["vips_version" [:int] :int]
   :vips-version-string            ["vips_version_string" [] :string]})

(defonce ^:private state* (atom nil))
(defonce ^:private type-value-cache* (atom nil))

(def ^:private vips-argument-required 1)
(def ^:private vips-argument-input 16)
(def ^:private vips-argument-output 32)

(defn bind-symbols*
  [resolve-symbol]
  (reduce-kv
   (fn [native k [symbol-name arg-types return-type]]
     (assoc native
            k
            (ffi/cfn (resolve-symbol symbol-name)
                     arg-types
                     return-type)))
   {}
   native-symbol-specs))

(defn last-error-message
  [native]
  (some-> ((:vips-error-buffer native)) not-empty))

(defn clear-error!
  [native]
  ((:vips-error-clear native)))

(defn throw-vips-error
  [native message data]
  (let [error-message (last-error-message native)]
    (clear-error! native)
    (throw (ex-info message
                    (cond-> data
                      error-message (assoc :vips/error error-message))))))

(defn build-gtypes
  [native]
  {:boolean      ((:g-type-from-name native) "gboolean")
   :boxed        ((:g-type-from-name native) "GBoxed")
   :double       ((:g-type-from-name native) "gdouble")
   :enum         ((:g-type-from-name native) "GEnum")
   :flags        ((:g-type-from-name native) "GFlags")
   :image        ((:image-get-type native))
   :int          ((:g-type-from-name native) "gint")
   :int64        ((:g-type-from-name native) "gint64")
   :long         ((:g-type-from-name native) "glong")
   :object       ((:g-type-from-name native) "GObject")
   :operation    ((:operation-get-type native))
   :string       ((:g-type-from-name native) "gchararray")
   :uint         ((:g-type-from-name native) "guint")
   :uint64       ((:g-type-from-name native) "guint64")
   :array-image  ((:array-image-get-type native))
   :array-double ((:array-double-get-type native))})

(defn- apply-block-untrusted-operations!
  [native blocked?]
  ((:vips-block-untrusted-set native) (if blocked? 1 0)))

(defn initialize-native-state
  [load-state]
  (let [native     (bind-symbols* (:resolve-symbol load-state))
        base-state (dissoc load-state :resolve-symbol)
        init-code  (int ((:vips-init native) "ol.vips"))
        _          (when-not (zero? init-code)
                     (throw-vips-error native
                                       "Failed to initialize libvips"
                                       {:exit-code init-code}))
        _          (apply-block-untrusted-operations! native true)
        version    ((:vips-version-string native))]
    (merge base-state
           {:bindings                    native
            :block-untrusted-operations? true
            :gtypes                      (build-gtypes native)
            :version-string              version})))

(defn ensure-initialized!
  []
  (or @state*
      (locking state*
        (or @state*
            (let [state (initialize-native-state (loader/load-native!))]
              (reset! state* state)
              state)))))

(defn set-block-untrusted-operations!
  [blocked?]
  (let [blocked?      (require-boolean blocked? "blocked?")
        current-state (ensure-initialized!)
        next-state    (assoc current-state :block-untrusted-operations? blocked?)]
    (apply-block-untrusted-operations! (:bindings current-state) blocked?)
    (when (identical? @state* current-state)
      (reset! state* next-state))
    next-state))

(defn bindings
  ([] (:bindings (ensure-initialized!)))
  ([k] (get (bindings) k))
  ([k not-found] (get (bindings) k not-found)))

(defn gtypes
  []
  (:gtypes (ensure-initialized!)))

(defn type-name
  [gtype]
  ((bindings :g-type-name) gtype))

(defn type-fundamental
  [gtype]
  ((bindings :g-type-fundamental) gtype))

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

(defn unsigned-int32
  [value]
  (bit-and 0xffffffff (long value)))

(defn classify-gtype
  [gtype]
  (let [gtypes      (gtypes)
        fundamental (type-fundamental gtype)]
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
  (ffi/read (ffi/reinterpret ptr (ffi/sizeof type)) type))

(defn- describe-argument
  [native op name pspec-ptr]
  (let [flags      ((:object-get-arg-flags native) op name)
        priority   ((:object-get-arg-priority native) op name)
        value-type (:value-type (deserialize-struct pspec-ptr g-param-spec))
        kind       (classify-gtype value-type)]
    (cond-> {:name       name
             :blurb      ((:param-spec-get-blurb native) pspec-ptr)
             :flags      flags
             :gtype      value-type
             :kind       kind
             :value-type (type-name value-type)
             :priority   priority
             :input?     (bit-set? flags vips-argument-input)
             :output?    (bit-set? flags vips-argument-output)
             :required?  (bit-set? flags vips-argument-required)}
      (= :int kind)
      (merge (select-keys (deserialize-struct pspec-ptr g-param-spec-int)
                          [:minimum :maximum])))))

(defn open-operation
  [operation-name]
  (let [operation-name (require-java-string operation-name "operation name")
        op             ((bindings :operation-new) operation-name)]
    (when (ffi/null? op)
      (throw (ex-info "Unknown libvips operation"
                      {:operation operation-name})))
    op))

(defn operation-arguments
  [operation-name]
  (ensure-initialized!)
  (let [argument-map (bindings :argument-map)
        op           (open-operation operation-name)]
    (try
      (with-open [arena (ffi/confined-arena)]
        (let [args     (volatile! [])
              callback (ffi/callback arena
                                     (fn [_object pspec-ptr _arg-class-ptr _instance _user-data _extra]
                                       (let [name ((bindings :param-spec-get-name) pspec-ptr)]
                                         (vswap! args conj (describe-argument (bindings) op name pspec-ptr)))
                                       ffi/null)
                                     [:pointer :pointer :pointer :pointer :pointer :pointer]
                                     :pointer)]
          (argument-map op callback ffi/null ffi/null)
          (->> @args
               (sort-by (juxt (complement :required?) :priority))
               vec)))
      (finally
        ((bindings :g-object-unref) op)))))

(defn operation-info
  [operation-name]
  (ensure-initialized!)
  (let [op (open-operation operation-name)]
    (try
      {:name        operation-name
       :description ((bindings :object-get-description) op)
       :args        (operation-arguments operation-name)}
      (finally
        ((bindings :g-object-unref) op)))))

(defn- discover-value-types
  []
  (ensure-initialized!)
  (let [enum-type  (:enum (gtypes))
        flags-type (:flags (gtypes))]
    (with-open [arena (ffi/confined-arena)]
      (let [slot-size (ffi/sizeof :long)
            discover-children
            (fn [fundamental class-type value-type build-entry]
              (let [count-ptr (ffi/alloc arena :int)
                    children  ((bindings :g-type-children) fundamental count-ptr)
                    count     (ffi/read count-ptr :int)
                    children* (ffi/reinterpret children (* count slot-size))]
                (into {}
                      (for [index (range count)
                            :let  [offset     (* index slot-size)
                                   child-type (ffi/read (ffi/slice children* offset slot-size) :long)
                                   type-name   (type-name child-type)
                                   class-ptr   ((bindings :g-type-class-ref) child-type)]
                            :when (and type-name (not (ffi/null? class-ptr)))]
                        (try
                          (let [class*      (ffi/reinterpret class-ptr (ffi/sizeof class-type))
                                value-class (ffi/read class* class-type)
                                n-values    (:n-values value-class)
                                value-size  (ffi/sizeof value-type)
                                values*     (ffi/reinterpret (:values value-class) (* n-values value-size))
                                entries     (into {}
                                                  (for [i     (range n-values)
                                                        :let  [entry (ffi/read
                                                                      (ffi/slice values* (* i value-size) value-size)
                                                                      value-type)
                                                               keyword (enum-keyword (:value-nick entry)
                                                                                     (:value-name entry))]
                                                        :when (not= keyword :last)]
                                                    [keyword (unsigned-int32 (:value entry))]))]
                            [type-name (build-entry type-name value-class entries)])
                          (finally
                            ((bindings :g-type-class-unref) class-ptr)))))))]
        (merge
         (discover-children enum-type
                            g-enum-class
                            g-enum-value
                            (fn [type-name _ entries]
                              {:type-name      type-name
                               :kind           :enum
                               :keyword->value entries
                               :value->keyword (into {} (map (fn [[k v]] [v k]) entries))}))
         (discover-children flags-type
                            g-flags-class
                            g-flags-value
                            (fn [type-name flags-class entries]
                              {:type-name      type-name
                               :kind           :flags
                               :keyword->value entries
                               :value->keyword (into {} (map (fn [[k v]] [v k]) entries))
                               :mask           (unsigned-int32 (:mask flags-class))})))))))

(defn- type-value-registry
  []
  (or @type-value-cache*
      (let [registry (discover-value-types)]
        (reset! type-value-cache* registry)
        registry)))

(defn describe-enum
  [enum-type-name]
  (or (let [entry (get (type-value-registry) enum-type-name)]
        (when (= :enum (:kind entry))
          entry))
      (throw (ex-info "Unknown enum type"
                      {:enum-type enum-type-name}))))

(defn describe-flags
  [flags-type-name]
  (or (let [entry (get (type-value-registry) flags-type-name)]
        (when (= :flags (:kind entry))
          entry))
      (throw (ex-info "Unknown flags type"
                      {:flags-type flags-type-name}))))

(defn encode-enum
  [enum-type-name value]
  (if (integer? value)
    (or (get-in (describe-enum enum-type-name) [:value->keyword (unsigned-int32 value)])
        (throw (ex-info "Unknown enum integer"
                        {:enum-type enum-type-name
                         :value     value})))
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

(defn encode-flags
  [flags-type-name value]
  (let [value          (unsigned-int32 (require-uint32 value (str "Flags argument `" flags-type-name "`")))
        {:keys [mask]} (describe-flags flags-type-name)
        unknown        (bit-and value (bit-xor 0xffffffff mask))]
    (if (zero? unknown)
      value
      (throw (ex-info "Unknown flags bits"
                      {:flags-type flags-type-name
                       :value      value
                       :mask       mask
                       :unknown    unknown})))))

(defn require-param-spec-int-range
  [{:keys [name minimum maximum]} value]
  (if (<= minimum value maximum)
    value
    (throw (ex-info (str "Operation argument `" name "` must be between " minimum " and " maximum)
                    {:argument name
                     :expected [:integer :range]
                     :minimum  minimum
                     :maximum  maximum
                     :value    value}))))

(defn set-operation-block!
  [name blocked?]
  (let [name     (require-java-string name "operation block name")
        blocked? (require-boolean blocked? "blocked?")]
    ((bindings :vips-operation-block-set) name (if blocked? 1 0))
    {:name     name
     :blocked? blocked?}))

(defn operation-cache-settings
  []
  (let [native (bindings)]
    {:max       ((:vips-cache-get-max native))
     :size      ((:vips-cache-get-size native))
     :max-mem   ((:vips-cache-get-max-mem native))
     :max-files ((:vips-cache-get-max-files native))}))

(defn set-operation-cache-max!
  [max]
  (let [native (bindings)
        max    (require-int32 max "cache max")]
    ((:vips-cache-set-max native) max)
    (operation-cache-settings)))

(defn set-operation-cache-max-mem!
  [max-mem]
  (let [native  (bindings)
        max-mem (unchecked-long (require-uint64 max-mem "cache max-mem"))]
    ((:vips-cache-set-max-mem native) max-mem)
    (operation-cache-settings)))

(defn set-operation-cache-max-files!
  [max-files]
  (let [native    (bindings)
        max-files (require-int32 max-files "cache max-files")]
    ((:vips-cache-set-max-files native) max-files)
    (operation-cache-settings)))

(defn disable-operation-cache!
  []
  (set-operation-cache-max! 0))

(defn tracked-resources
  []
  (let [native (bindings)]
    {:mem           ((:vips-tracked-get-mem native))
     :mem-highwater ((:vips-tracked-get-mem-highwater native))
     :allocs        ((:vips-tracked-get-allocs native))
     :files         ((:vips-tracked-get-files native))}))

(defn coerce-option-value
  [value]
  (cond
    (keyword? value) (name value)
    (some? (coerce-java-string value)) (require-java-string value "option value")
    (boolean? value) (if value "true" "false")
    (number? value) (str value)
    (sequential? value) (str/join " " (map coerce-option-value value))
    :else (throw (ex-info "Option value must be a keyword, string, java.nio.file.Path, java.io.File, CharSequence, boolean, number, or sequential collection of those"
                          {:expected [:keyword :string :path :file :char-sequence :boolean :number :sequential]
                           :value    value}))))

(defn require-field-name
  [value]
  (require-java-string value "image field name"))

(defn render-option-value
  [value]
  (coerce-option-value value))

(defn render-option-string
  [opts]
  (when (seq opts)
    (str "["
         (->> opts
              (sort-by (comp str key))
              (map (fn [[k v]]
                     (str (require-name-string k "option name")
                          "="
                          (render-option-value v))))
              (str/join ","))
         "]")))

(defn append-options
  [value opts]
  (let [value         (require-java-string value "option target")
        option-string (render-option-string opts)]
    (if-not option-string
      value
      (if (and (str/includes? value "[")
               (str/ends-with? value "]"))
        (str (subs value 0 (dec (count value)))
             ","
             (subs option-string 1))
        (str value option-string)))))

(defn maybe-find-load-operation-name
  [source]
  (let [native         (bindings)
        source         (require-java-string source "from-file source")
        operation-name ((:foreign-find-load native) source)]
    (when-not operation-name
      (clear-error! native))
    operation-name))

(defn maybe-find-save-operation-name
  [path]
  (let [native         (bindings)
        path           (require-java-string path "write-to-file path")
        operation-name ((:foreign-find-save native) path)]
    (when-not operation-name
      (clear-error! native))
    operation-name))

(defn maybe-find-save-buffer-operation-name
  [suffix]
  (let [native         (bindings)
        suffix         (require-java-string suffix "write-to-buffer suffix")
        operation-name ((:foreign-find-save-buffer native) suffix)]
    (when-not operation-name
      (clear-error! native))
    operation-name))

(defn maybe-find-save-target-operation-name
  [suffix]
  (let [native         (bindings)
        suffix         (require-java-string suffix "write-to-stream suffix")
        operation-name ((:foreign-find-save-target native) suffix)]
    (when-not operation-name
      (clear-error! native))
    operation-name))

(def ^:private helper-option-integer-pattern
  #"^[+-]?\d+$")

(defn- helper-option-value
  [{:keys [kind]} value]
  (cond
    (and (= :int kind) (integer? value))
    value

    (and (= :flags kind) (integer? value))
    value

    (and (#{:int :flags} kind)
         (string? value)
         (re-matches helper-option-integer-pattern value))
    (bigint value)

    :else
    nil))

(defn validate-helper-option-values!
  [operation-name option-values]
  (when (seq option-values)
    (let [arg-by-name (into {} (map (juxt :name identity) (operation-arguments operation-name)))]
      (doseq [[k raw-value] option-values
              :let          [arg-name (require-name-string k "option name")
                             arg      (get arg-by-name arg-name)
                             value    (and arg (helper-option-value arg raw-value))]
              :when         value]
        (case (:kind arg)
          :int (let [label (str "Operation argument `" (:name arg) "`")
                     value (require-int32 value label)
                     value (if (and (some? (:minimum arg)) (some? (:maximum arg)))
                             (require-param-spec-int-range arg value)
                             value)]
                 value)
          :flags (encode-flags (:value-type arg) value)
          nil)))))

(defn validate-helper-target-options!
  [find-operation target label opts]
  (when (seq opts)
    (let [target (require-java-string target label)]
      (when-let [operation-name (find-operation target)]
        (validate-helper-option-values! operation-name opts)))))

(defprotocol PointerBacked
  (pointer ^java.lang.foreign.MemorySegment [this]))

(deftype OperationResult [result-map ^AtomicBoolean closed?]
  clojure.lang.ILookup
  (valAt [_ key]
    (get result-map key))
  (valAt [_ key not-found]
    (get result-map key not-found))

  clojure.lang.Associative
  (assoc [_ key value]
    (assoc result-map key value))
  (containsKey [_ key]
    (contains? result-map key))
  (entryAt [_ key]
    (find result-map key))

  clojure.lang.IPersistentMap
  (without [_ key]
    (dissoc result-map key))

  clojure.lang.Seqable
  (seq [_]
    (seq result-map))

  clojure.lang.Counted
  (count [_]
    (count result-map))

  clojure.lang.IPersistentCollection
  (cons [_ entry]
    (cons entry result-map))
  (empty [_]
    {})
  (equiv [_ other]
    (= result-map other))

  java.lang.Iterable
  (iterator [_]
    (.iterator ^Iterable result-map))

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      (doseq [value (vals result-map)]
        (when (instance? java.lang.AutoCloseable value)
          (.close ^java.lang.AutoCloseable value)))))

  Object
  (equals [_ other]
    (= result-map other))
  (hashCode [_]
    (hash result-map))
  (toString [_]
    (str result-map)))

(defn- throw-closed-image-handle
  []
  (throw (ex-info "Cannot use closed image handle"
                  {:type :ol.vips/closed-image-handle})))

(deftype ImageHandle [ptr ^AtomicBoolean closed? keeper]
  PointerBacked
  (pointer [_]
    (if (.get closed?)
      (throw-closed-image-handle)
      ptr))

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      ((bindings :g-object-unref) ptr)
      (when keeper
        (.close ^java.lang.AutoCloseable keeper))))

  Object
  (toString [_]
    (str "#<ol.vips.impl.api.ImageHandle " ptr ">")))

(deftype StreamBridge [ptr ^Arena arena stream callbacks ^AtomicReference failure-ref close-stream! ^AtomicBoolean closed?]
  PointerBacked
  (pointer [_] ptr)

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      (try
        ((bindings :g-object-unref) ptr)
        (finally
          (try
            (close-stream!)
            (finally
              (.close arena)))))))

  Object
  (toString [_]
    (str "#<ol.vips.impl.api.StreamBridge " ptr ">")))

(defn throw-stream-error
  [message data ^AtomicReference failure-ref]
  (let [native          (bindings)
        callback-error  (.get failure-ref)
        libvips-message (last-error-message native)]
    (clear-error! native)
    (throw (ex-info message
                    (cond-> data
                      libvips-message (assoc :vips/error libvips-message)
                      callback-error  (assoc :stream/error       (.getMessage ^Throwable callback-error)
                                             :stream/error-class (.getName (class callback-error))))
                    callback-error))))

(defn require-instance
  [^Class expected value label]
  (when-not (instance? expected value)
    (throw (ex-info (str label " must be a " (.getName expected))
                    {:expected (.getName expected)
                     :value    value})))
  value)

(defn remember-stream-failure!
  [^AtomicReference failure-ref ^Throwable throwable]
  (.compareAndSet failure-ref nil throwable)
  throwable)

(defn close-quietly
  [closeable]
  (when closeable
    (try
      (.close ^java.lang.AutoCloseable closeable)
      (catch Throwable _))))

(defn connect-signal!
  [ptr signal callback callback-type arena]
  (let [stub      (ffi/callback arena callback (first callback-type) (second callback-type))
        signal-id ((bindings :g-signal-connect-data) ptr signal stub nil nil 0)]
    (when (zero? signal-id)
      (throw-vips-error (bindings)
                        "Failed to connect custom stream callback"
                        {:signal signal}))
    {:callback callback
     :stub     stub}))

(defn stream-failure-ref
  [^StreamBridge bridge]
  (.failure-ref bridge))

(defn- new-source-bridge*
  [^InputStream stream close-stream!]
  (let [arena       (Arena/ofShared)
        failure-ref (AtomicReference. nil)
        ptr         ((bindings :source-custom-new))]
    (when (ffi/null? ptr)
      (.close arena)
      (throw-vips-error (bindings)
                        "Failed to create custom stream source"
                        {}))
    (try
      (let [read-callback (fn [_source data length _handle]
                            (try
                              (when (neg? length)
                                (throw (ex-info "Custom stream source received a negative read length"
                                                {:length length})))
                              (let [requested (int (min length Integer/MAX_VALUE))
                                    chunk     (.readNBytes stream requested)
                                    read-size (alength ^bytes chunk)]
                                (when (pos? read-size)
                                  (ffi/write-array (ffi/reinterpret data requested) :byte chunk))
                                (long read-size))
                              (catch Throwable t
                                (remember-stream-failure! failure-ref t)
                                -1)))
            read-signal   (connect-signal! ptr "read" read-callback source-read-callback-type arena)]
        (StreamBridge. ptr
                       arena
                       stream
                       [(:callback read-signal) (:stub read-signal)]
                       failure-ref
                       close-stream!
                       (AtomicBoolean. false)))
      (catch Throwable t
        ((bindings :g-object-unref) ptr)
        (.close arena)
        (close-stream!)
        (throw t)))))

(defn new-source-bridge
  [^InputStream stream]
  (new-source-bridge* stream #(close-quietly stream)))

(def ^:private stream-validation-mark-limit
  Integer/MAX_VALUE)

(defn rewindable-input-stream
  [source]
  (let [stream (require-instance InputStream source "from-stream source")]
    (if (.markSupported ^InputStream stream)
      stream
      (java.io.BufferedInputStream. stream))))

(defn maybe-find-load-source-operation-name
  [^InputStream source]
  (let [native (bindings)]
    (.mark source stream-validation-mark-limit)
    (try
      (let [operation-name (with-open [^StreamBridge bridge (new-source-bridge* source (fn []))]
                             ((:foreign-find-load-source native) (pointer bridge)))]
        (when-not operation-name
          (clear-error! native))
        operation-name)
      (finally
        (.reset source)))))

(defn validated-stream-source
  [source opts]
  (let [stream (rewindable-input-stream source)]
    (when-let [operation-name (maybe-find-load-source-operation-name stream)]
      (validate-helper-option-values! operation-name opts))
    stream))

(defn finish-output-stream!
  [^OutputStream stream ^AtomicReference failure-ref]
  (try
    (.flush stream)
    (.close stream)
    (int 0)
    (catch Throwable t
      (remember-stream-failure! failure-ref t)
      (close-quietly stream)
      (int -1))))

(defn new-target-bridge
  [^OutputStream stream]
  (let [arena       (Arena/ofShared)
        failure-ref (AtomicReference. nil)
        ptr         ((bindings :target-custom-new))]
    (when (ffi/null? ptr)
      (.close arena)
      (throw-vips-error (bindings)
                        "Failed to create custom stream target"
                        {}))
    (try
      (let [write-callback (fn [_target data length _handle]
                             (try
                               (when (neg? length)
                                 (throw (ex-info "Custom stream target received a negative write length"
                                                 {:length length})))
                               (let [write-size (Math/toIntExact length)]
                                 (when (pos? write-size)
                                   (let [chunk (ffi/read-array (ffi/reinterpret data length) :byte write-size)]
                                     (.write ^OutputStream stream ^bytes chunk (int 0) (int write-size))))
                                 (long write-size))
                               (catch Throwable t
                                 (remember-stream-failure! failure-ref t)
                                 -1)))
            end-callback   (fn [_target _handle]
                             (finish-output-stream! stream failure-ref))
            write-signal   (connect-signal! ptr "write" write-callback target-write-callback-type arena)
            end-signal     (connect-signal! ptr "end" end-callback target-end-callback-type arena)]
        (StreamBridge. ptr
                       arena
                       stream
                       [(:callback write-signal) (:stub write-signal)
                        (:callback end-signal) (:stub end-signal)]
                       failure-ref
                       #(close-quietly stream)
                       (AtomicBoolean. false)))
      (catch Throwable t
        ((bindings :g-object-unref) ptr)
        (.close arena)
        (close-quietly stream)
        (throw t)))))

(defn wrap-image
  ([ptr]
   (wrap-image ptr nil))
  ([ptr keeper]
   (when-not (ffi/null? ptr)
     (ImageHandle. ptr (AtomicBoolean. false) keeper))))

(defn adopt-image
  [ptr]
  (wrap-image ptr))

(defn operation-result
  [result-map]
  (cond
    (and (= #{:out} (set (keys result-map)))
         (satisfies? PointerBacked (:out result-map)))
    (:out result-map)

    (contains? result-map :out)
    (OperationResult. result-map (AtomicBoolean. false))

    :else
    result-map))

(defn image-handle
  [value]
  (cond
    (satisfies? PointerBacked value)
    value

    (and (map? value) (contains? value :out))
    (let [image (:out value)]
      (if (satisfies? PointerBacked image)
        image
        (throw (ex-info "Operation result map does not contain an image at :out"
                        {:value value}))))

    :else
    (throw (ex-info "Expected an image handle or operation result map"
                    {:value value}))))

(defn with-gvalue
  [gtype f]
  (with-open [arena (ffi/confined-arena)]
    (let [value (ffi/alloc arena g-value)]
      ((bindings :g-value-init) value gtype)
      (try
        (f value)
        (finally
          ((bindings :g-value-unset) value))))))

(defn open-image
  ([source]
   (let [path  (require-java-string source "from-file source")
         image ((bindings :image-new-from-file) path nil)]
     (when (ffi/null? image)
       (throw-vips-error (bindings)
                         "Failed to open image"
                         {:source path}))
     (wrap-image image)))
  ([source opts]
   (validate-helper-target-options! maybe-find-load-operation-name source "from-file source" opts)
   (let [path  (append-options source opts)
         image ((bindings :image-new-from-file) path nil)]
     (when (ffi/null? image)
       (throw-vips-error (bindings)
                         "Failed to open image"
                         {:source path}))
     (wrap-image image))))

(def ^:private byte-array-class
  (class (byte-array 0)))

(def ^:private missing-field-sentinel
  (Object.))

(def ^:private c-string-max-bytes
  65536)

(defn ->byte-array
  ([value]
   (->byte-array value "image bytes"))
  ([value label]
   (cond
     (instance? byte-array-class value) value
     (sequential? value) (byte-array (map (fn [item]
                                            (let [item (require-integer item label)]
                                              (cond
                                                (<= -128 item 127) (byte item)
                                                (<= 0 item 255) (unchecked-byte item)
                                                :else (throw (ex-info (str label " must contain integers in byte range")
                                                                      {:label    label
                                                                       :expected [:byte-range]
                                                                       :value    item})))))
                                          value))
     :else (throw (ex-info "Expected image bytes"
                           {:label label
                            :value value})))))

(defn maybe-find-load-buffer-operation-name
  [source]
  (let [native (bindings)
        data   (->byte-array source "from-buffer source")]
    (with-open [arena (ffi/confined-arena)]
      (let [size   (alength ^bytes data)
            buffer (ffi/alloc arena (max 1 size) 1)]
        (ffi/write-array buffer :byte data)
        (let [operation-name ((:foreign-find-load-buffer native) buffer size)]
          (when-not operation-name
            (clear-error! native))
          operation-name)))))

(defn- open-image-from-buffer*
  [source option-string]
  (let [data   (->byte-array source "from-buffer source")
        arena  (Arena/ofShared)
        size   (alength ^bytes data)
        buffer (ffi/alloc arena (max 1 size) 1)
        image  (do
                 (ffi/write-array buffer :byte data)
                 ((bindings :image-new-from-buffer) buffer size option-string nil))]
    (when (ffi/null? image)
      (.close arena)
      (throw-vips-error (bindings)
                        "Failed to open image from buffer"
                        {:byte-count size}))
    (wrap-image image arena)))

(defn open-image-from-buffer
  ([source]
   (open-image-from-buffer* source ""))
  ([source opts]
   (when-let [operation-name (maybe-find-load-buffer-operation-name source)]
     (validate-helper-option-values! operation-name opts))
   (open-image-from-buffer* source (append-options "" opts))))

(defn- open-image-from-stream*
  [source option-string]
  (let [stream (require-instance InputStream source "from-stream source")
        bridge (new-source-bridge stream)
        image  ((bindings :image-new-from-source) (pointer bridge) (or option-string "") nil)]
    (when (ffi/null? image)
      (.close ^java.lang.AutoCloseable bridge)
      (throw-stream-error "Failed to open image from stream"
                          {}
                          (stream-failure-ref bridge)))
    (wrap-image image bridge)))

(defn open-image-from-stream
  ([source]
   (open-image-from-stream* source ""))
  ([source opts]
   (open-image-from-stream* (validated-stream-source source opts)
                            (append-options "" opts))))

(defn copy-image-to-memory
  [image]
  (let [copied ((bindings :image-copy-memory) (pointer (image-handle image)))]
    (when (ffi/null? copied)
      (throw-vips-error (bindings)
                        "Failed to copy image to memory"
                        {}))
    (wrap-image copied)))

(defn write-image!
  ([image path]
   (let [path (require-java-string path "write-to-file path")
         code ((bindings :image-write-to-file) (pointer (image-handle image)) path nil)]
     (when-not (zero? code)
       (throw-vips-error (bindings)
                         "Failed to write image"
                         {:path path}))
     image))
  ([image path opts]
   (validate-helper-target-options! maybe-find-save-operation-name path "write-to-file path" opts)
   (let [path (append-options path opts)
         code ((bindings :image-write-to-file) (pointer (image-handle image)) path nil)]
     (when-not (zero? code)
       (throw-vips-error (bindings)
                         "Failed to write image"
                         {:path path}))
     image)))

(defn write-image-to-buffer
  ([image suffix]
   (with-open [arena (ffi/confined-arena)]
     (let [suffix     (require-java-string suffix "write-to-buffer suffix")
           buffer-ptr (ffi/alloc arena :pointer)
           size-ptr   (ffi/alloc arena :size_t)
           code       ((bindings :image-write-to-buffer)
                       (pointer (image-handle image))
                       suffix
                       buffer-ptr
                       size-ptr
                       nil)]
       (when-not (zero? code)
         (throw-vips-error (bindings)
                           "Failed to write image to buffer"
                           {:suffix suffix}))
       (let [output-ptr  (ffi/read buffer-ptr :pointer)
             output-size (ffi/read size-ptr :long)]
         (try
           (ffi/read-array (ffi/reinterpret output-ptr output-size) :byte output-size)
           (finally
             ((bindings :g-free) output-ptr)))))))
  ([image suffix opts]
   (validate-helper-target-options! maybe-find-save-buffer-operation-name suffix "write-to-buffer suffix" opts)
   (with-open [arena (ffi/confined-arena)]
     (let [suffix     (append-options suffix opts)
           buffer-ptr (ffi/alloc arena :pointer)
           size-ptr   (ffi/alloc arena :size_t)
           code       ((bindings :image-write-to-buffer)
                       (pointer (image-handle image))
                       suffix
                       buffer-ptr
                       size-ptr
                       nil)]
       (when-not (zero? code)
         (throw-vips-error (bindings)
                           "Failed to write image to buffer"
                           {:suffix suffix}))
       (let [output-ptr  (ffi/read buffer-ptr :pointer)
             output-size (ffi/read size-ptr :long)]
         (try
           (ffi/read-array (ffi/reinterpret output-ptr output-size) :byte output-size)
           (finally
             ((bindings :g-free) output-ptr))))))))

(defn write-image-to-stream
  ([image sink suffix]
   (let [stream (require-instance OutputStream sink "write-to-stream sink")]
     (with-open [^StreamBridge bridge (new-target-bridge stream)]
       (let [suffix (require-java-string suffix "write-to-stream suffix")
             code   ((bindings :image-write-to-target)
                     (pointer (image-handle image))
                     suffix
                     (pointer bridge)
                     nil)]
         (when-not (zero? code)
           (throw-stream-error "Failed to write image to stream"
                               {:suffix suffix}
                               (stream-failure-ref bridge)))
         image))))
  ([image sink suffix opts]
   (validate-helper-target-options! maybe-find-save-target-operation-name suffix "write-to-stream suffix" opts)
   (let [stream (require-instance OutputStream sink "write-to-stream sink")]
     (with-open [^StreamBridge bridge (new-target-bridge stream)]
       (let [suffix (append-options suffix opts)
             code   ((bindings :image-write-to-target)
                     (pointer (image-handle image))
                     suffix
                     (pointer bridge)
                     nil)]
         (when-not (zero? code)
           (throw-stream-error "Failed to write image to stream"
                               {:suffix suffix}
                               (stream-failure-ref bridge)))
         image)))))

(defn image-width
  [image]
  ((bindings :image-get-width) (pointer (image-handle image))))

(defn image-height
  [image]
  ((bindings :image-get-height) (pointer (image-handle image))))

(defn image-bands
  [image]
  ((bindings :image-get-bands) (pointer (image-handle image))))

(defn image-has-alpha?
  [image]
  (not (zero? ((bindings :image-has-alpha) (pointer (image-handle image))))))

(defn image-info
  [image]
  {:width      (image-width image)
   :height     (image-height image)
   :bands      (image-bands image)
   :has-alpha? (image-has-alpha? image)})

(defn image-field-type
  [image field-name]
  (let [field-name (require-field-name field-name)]
    ((bindings :image-get-typeof) (pointer (image-handle image)) field-name)))

(defn image-has-field?
  [image field-name]
  (not (zero? (image-field-type image field-name))))

(defn read-c-string
  [ptr]
  (.getString ^java.lang.foreign.MemorySegment
   (.reinterpret ^java.lang.foreign.MemorySegment ptr c-string-max-bytes)
              0))

(defn image-field-names
  [image]
  (let [raw-fields ((bindings :image-get-fields) (pointer (image-handle image)))]
    (if (ffi/null? raw-fields)
      []
      (let [slot-size  (ffi/sizeof :pointer)
            max-fields 1024
            fields-ptr (.reinterpret ^java.lang.foreign.MemorySegment raw-fields
                                     (* max-fields slot-size))]
        (try
          (loop [idx 0
                 acc []]
            (let [field-ptr (.getAtIndex ^java.lang.foreign.MemorySegment fields-ptr
                                         java.lang.foreign.ValueLayout/ADDRESS
                                         idx)]
              (if (ffi/null? field-ptr)
                acc
                (recur (inc idx) (conj acc (read-c-string field-ptr))))))
          (finally
            ((bindings :g-strfreev) raw-fields)))))))

(defn image-field-as-string
  ([image field-name]
   (image-field-as-string image field-name missing-field-sentinel))
  ([image field-name not-found]
   (let [field-name (require-field-name field-name)]
     (if-not (image-has-field? image field-name)
       (if (identical? not-found missing-field-sentinel) nil not-found)
       (with-open [arena (ffi/confined-arena)]
         (let [out-ptr (ffi/alloc arena :pointer)
               code    ((bindings :image-get-as-string)
                        (pointer (image-handle image))
                        field-name
                        out-ptr)]
           (when-not (zero? code)
             (throw-vips-error (bindings)
                               "Failed to read image metadata field as string"
                               {:field field-name}))
           (let [value-ptr (ffi/read out-ptr :pointer)]
             (try
               (read-c-string value-ptr)
               (finally
                 ((bindings :g-free) value-ptr))))))))))

(defn image-int-field
  [image field-name]
  (let [field-name (require-field-name field-name)]
    (with-open [arena (ffi/confined-arena)]
      (let [out-ptr (ffi/alloc arena :int)
            code    ((bindings :image-get-int)
                     (pointer (image-handle image))
                     field-name
                     out-ptr)]
        (when-not (zero? code)
          (throw-vips-error (bindings)
                            "Failed to read integer image metadata field"
                            {:field field-name}))
        (ffi/read out-ptr :int)))))

(defn image-double-field
  [image field-name]
  (let [field-name (require-field-name field-name)]
    (with-open [arena (ffi/confined-arena)]
      (let [out-ptr (ffi/alloc arena :double)
            code    ((bindings :image-get-double)
                     (pointer (image-handle image))
                     field-name
                     out-ptr)]
        (when-not (zero? code)
          (throw-vips-error (bindings)
                            "Failed to read double image metadata field"
                            {:field field-name}))
        (ffi/read out-ptr :double)))))

(defn image-string-field
  [image field-name]
  (let [field-name (require-field-name field-name)]
    (with-open [arena (ffi/confined-arena)]
      (let [out-ptr (ffi/alloc arena :pointer)
            code    ((bindings :image-get-string)
                     (pointer (image-handle image))
                     field-name
                     out-ptr)]
        (when-not (zero? code)
          (throw-vips-error (bindings)
                            "Failed to read string image metadata field"
                            {:field field-name}))
        (read-c-string (ffi/read out-ptr :pointer))))))

(defn image-array-int-field
  [image field-name]
  (let [field-name (require-field-name field-name)]
    (with-open [arena (ffi/confined-arena)]
      (let [out-ptr (ffi/alloc arena :pointer)
            n-ptr   (ffi/alloc arena :int)
            code    ((bindings :image-get-array-int)
                     (pointer (image-handle image))
                     field-name
                     out-ptr
                     n-ptr)]
        (when-not (zero? code)
          (throw-vips-error (bindings)
                            "Failed to read integer-array image metadata field"
                            {:field field-name}))
        (let [count     (ffi/read n-ptr :int)
              data-ptr  (.reinterpret ^java.lang.foreign.MemorySegment
                         (ffi/read out-ptr :pointer)
                                      (* count (ffi/sizeof :int)))
              slot-size (ffi/sizeof :int)]
          (mapv (fn [idx]
                  (.get ^java.lang.foreign.MemorySegment
                   data-ptr
                        java.lang.foreign.ValueLayout/JAVA_INT
                        (long (* idx slot-size))))
                (range count)))))))

(defn image-array-double-field
  [image field-name]
  (let [field-name (require-field-name field-name)]
    (with-open [arena (ffi/confined-arena)]
      (let [out-ptr (ffi/alloc arena :pointer)
            n-ptr   (ffi/alloc arena :int)
            code    ((bindings :image-get-array-double)
                     (pointer (image-handle image))
                     field-name
                     out-ptr
                     n-ptr)]
        (when-not (zero? code)
          (throw-vips-error (bindings)
                            "Failed to read double-array image metadata field"
                            {:field field-name}))
        (let [count     (ffi/read n-ptr :int)
              data-ptr  (.reinterpret ^java.lang.foreign.MemorySegment
                         (ffi/read out-ptr :pointer)
                                      (* count (ffi/sizeof :double)))
              slot-size (ffi/sizeof :double)]
          (mapv (fn [idx]
                  (.get ^java.lang.foreign.MemorySegment
                   data-ptr
                        java.lang.foreign.ValueLayout/JAVA_DOUBLE
                        (long (* idx slot-size))))
                (range count)))))))

(defn image-blob-field
  [image field-name]
  (let [field-name (require-field-name field-name)]
    (with-open [arena (ffi/confined-arena)]
      (let [out-ptr (ffi/alloc arena :pointer)
            len-ptr (ffi/alloc arena :size_t)
            code    ((bindings :image-get-blob)
                     (pointer (image-handle image))
                     field-name
                     out-ptr
                     len-ptr)]
        (when-not (zero? code)
          (throw-vips-error (bindings)
                            "Failed to read blob image metadata field"
                            {:field field-name}))
        (let [data-ptr (ffi/read out-ptr :pointer)
              length   (ffi/read len-ptr :long)]
          (if (zero? length)
            (byte-array 0)
            (ffi/read-array (ffi/reinterpret data-ptr length) :byte length)))))))

(defn image-field
  ([image field-name]
   (image-field image field-name missing-field-sentinel))
  ([image field-name not-found]
   (let [gtype (image-field-type image field-name)]
     (if (zero? gtype)
       (if (identical? not-found missing-field-sentinel) nil not-found)
       (let [field-type-name (type-name gtype)]
         (case field-type-name
           ("gint" "guint") (image-int-field image field-name)
           ("gdouble") (image-double-field image field-name)
           ("gchararray" "VipsRefString") (image-string-field image field-name)
           ("VipsArrayInt") (image-array-int-field image field-name)
           ("VipsArrayDouble") (image-array-double-field image field-name)
           ("VipsBlob") (image-blob-field image field-name)
           (image-field-as-string image field-name)))))))

(defn image-metadata
  [image]
  (into {}
        (map (fn [field-name]
               [field-name (image-field image field-name)]))
        (image-field-names image)))

(defn infer-field-type
  [value]
  (cond
    (instance? byte-array-class value) :blob
    (some? (coerce-java-string value)) :string
    (integer? value) :int
    (number? value) :double
    (and (sequential? value) (every? integer? value)) :array-int
    (and (sequential? value) (every? number? value)) :array-double
    :else (throw (ex-info "Unsupported image metadata value"
                          {:value value}))))

(defn image-assoc-field!
  ([image field-name value]
   (image-assoc-field! image field-name value {}))
  ([image field-name value {:keys [type]}]
   (let [field-name (require-field-name field-name)
         type       (or type (infer-field-type value))
         image-ptr  (pointer (image-handle image))]
     (case type
       :int ((bindings :image-set-int) image-ptr field-name (int (require-int32 value "image field value")))
       :double ((bindings :image-set-double) image-ptr field-name (double (require-finite-number value "image field value")))
       :string ((bindings :image-set-string) image-ptr field-name (require-java-string value "image field value"))
       :blob (let [data (->byte-array value "image field value")
                   size (alength ^bytes data)]
               (with-open [arena (ffi/confined-arena)]
                 (let [buffer (ffi/alloc arena (max 1 size) 1)]
                   (when (pos? size)
                     (ffi/write-array buffer :byte data))
                   ((bindings :image-set-blob-copy) image-ptr field-name buffer size))))
       :array-int (let [values (vec value)
                        count  (count values)]
                    (with-open [arena (ffi/confined-arena)]
                      (let [data (ffi/alloc arena (* count (ffi/sizeof :int)) (ffi/alignof :int))]
                        (doseq [[idx item] (map-indexed vector values)]
                          (ffi/write data :int (int (require-int32 item "image field value")) (* idx (ffi/sizeof :int))))
                        ((bindings :image-set-array-int) image-ptr field-name data count))))
       :array-double (let [values (vec value)
                           count  (count values)]
                       (with-open [arena (ffi/confined-arena)]
                         (let [data (ffi/alloc arena (* count (ffi/sizeof :double)) (ffi/alignof :double))]
                           (doseq [[idx item] (map-indexed vector values)]
                             (ffi/write data :double (double (require-finite-number item "image field value")) (* idx (ffi/sizeof :double))))
                           ((bindings :image-set-array-double) image-ptr field-name data count))))
       (throw (ex-info "Unsupported image metadata type"
                       {:field field-name
                        :type  type
                        :value value})))
     image)))

(defn image-dissoc-field!
  [image field-name]
  ((bindings :image-remove) (pointer (image-handle image)) (require-field-name field-name))
  image)
