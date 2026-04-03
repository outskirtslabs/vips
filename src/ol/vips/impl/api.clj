(ns ^:no-doc ol.vips.impl.api
  (:require
   [clojure.string :as str]
   [coffi.ffi :as ffi]
   [coffi.layout :as layout]
   [coffi.mem :as mem]
   [ol.vips.impl.loader :as loader])
  (:import
   [java.io InputStream OutputStream]
   [java.lang.foreign Arena]
   [java.util.concurrent.atomic AtomicBoolean AtomicReference]))

(set! *warn-on-reflection* true)

(mem/defalias ::g-type ::mem/long)
(mem/defalias ::size-t ::mem/long)

(mem/defalias ::g-value
  (layout/with-c-layout
    [::mem/struct
     [[:g-type ::mem/long]
      [:data [::mem/array ::mem/long 2]]]]))

(def ^:private source-read-callback-type
  [::ffi/fn [::mem/pointer ::mem/pointer ::mem/long ::mem/pointer] ::mem/long :raw-fn? true])

(def ^:private target-write-callback-type
  [::ffi/fn [::mem/pointer ::mem/pointer ::mem/long ::mem/pointer] ::mem/long :raw-fn? true])

(def ^:private target-end-callback-type
  [::ffi/fn [::mem/pointer ::mem/pointer] ::mem/int :raw-fn? true])

(def ^:private native-symbol-specs
  {:g-free                         ["g_free" [::mem/pointer] ::mem/void]
   :g-strfreev                     ["g_strfreev" [::mem/pointer] ::mem/void]
   :g-signal-connect-data          ["g_signal_connect_data"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer ::mem/pointer ::mem/int]
                                    ::mem/long]
   :g-object-ref                   ["g_object_ref" [::mem/pointer] ::mem/pointer]
   :g-object-unref                 ["g_object_unref" [::mem/pointer] ::mem/void]
   :g-object-get-property          ["g_object_get_property"
                                    [::mem/pointer ::mem/c-string ::mem/pointer]
                                    ::mem/void]
   :g-object-set-property          ["g_object_set_property"
                                    [::mem/pointer ::mem/c-string ::mem/pointer]
                                    ::mem/void]
   :g-type-children                ["g_type_children" [::g-type ::mem/pointer] ::mem/pointer]
   :g-type-class-ref               ["g_type_class_ref" [::g-type] ::mem/pointer]
   :g-type-class-unref             ["g_type_class_unref" [::mem/pointer] ::mem/void]
   :g-type-fundamental             ["g_type_fundamental" [::g-type] ::g-type]
   :g-type-from-name               ["g_type_from_name" [::mem/c-string] ::g-type]
   :g-type-name                    ["g_type_name" [::g-type] ::mem/c-string]
   :param-spec-get-blurb           ["g_param_spec_get_blurb" [::mem/pointer] ::mem/c-string]
   :param-spec-get-name            ["g_param_spec_get_name" [::mem/pointer] ::mem/c-string]
   :nickname-find                  ["vips_nickname_find" [::g-type] ::mem/c-string]
   :argument-map                   ["vips_argument_map"
                                    [::mem/pointer
                                     [::ffi/fn [::mem/pointer
                                                ::mem/pointer
                                                ::mem/pointer
                                                ::mem/pointer
                                                ::mem/pointer
                                                ::mem/pointer]
                                      ::mem/pointer]
                                     ::mem/pointer
                                     ::mem/pointer]
                                    ::mem/pointer]
   :type-map-all                   ["vips_type_map_all"
                                    [::g-type
                                     [::ffi/fn [::g-type ::mem/pointer] ::mem/pointer]
                                     ::mem/pointer]
                                    ::mem/pointer]
   :g-value-get-boolean            ["g_value_get_boolean" [::mem/pointer] ::mem/int]
   :g-value-get-double             ["g_value_get_double" [::mem/pointer] ::mem/double]
   :g-value-get-enum               ["g_value_get_enum" [::mem/pointer] ::mem/int]
   :g-value-get-flags              ["g_value_get_flags" [::mem/pointer] ::mem/int]
   :g-value-get-int                ["g_value_get_int" [::mem/pointer] ::mem/int]
   :g-value-get-int64              ["g_value_get_int64" [::mem/pointer] ::mem/long]
   :g-value-get-object             ["g_value_get_object" [::mem/pointer] ::mem/pointer]
   :g-value-get-string             ["g_value_get_string" [::mem/pointer] ::mem/c-string]
   :g-value-get-uint               ["g_value_get_uint" [::mem/pointer] ::mem/int]
   :g-value-get-uint64             ["g_value_get_uint64" [::mem/pointer] ::mem/long]
   :g-value-init                   ["g_value_init" [::mem/pointer ::g-type] ::mem/pointer]
   :g-value-set-boolean            ["g_value_set_boolean" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-boxed              ["g_value_set_boxed" [::mem/pointer ::mem/pointer] ::mem/void]
   :g-value-set-double             ["g_value_set_double" [::mem/pointer ::mem/double] ::mem/void]
   :g-value-set-enum               ["g_value_set_enum" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-flags              ["g_value_set_flags" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-int                ["g_value_set_int" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-int64              ["g_value_set_int64" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-set-long               ["g_value_set_long" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-set-object             ["g_value_set_object" [::mem/pointer ::mem/pointer] ::mem/void]
   :g-value-set-string             ["g_value_set_string" [::mem/pointer ::mem/c-string] ::mem/void]
   :g-value-set-uint               ["g_value_set_uint" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-uint64             ["g_value_set_uint64" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-unset                  ["g_value_unset" [::mem/pointer] ::mem/void]
   :image-get-height               ["vips_image_get_height" [::mem/pointer] ::mem/int]
   :image-get-bands                ["vips_image_get_bands" [::mem/pointer] ::mem/int]
   :image-get                      ["vips_image_get" [::mem/pointer ::mem/c-string ::mem/pointer] ::mem/int]
   :image-get-array-double         ["vips_image_get_array_double"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer]
                                    ::mem/int]
   :image-get-array-int            ["vips_image_get_array_int"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer]
                                    ::mem/int]
   :image-get-as-string            ["vips_image_get_as_string"
                                    [::mem/pointer ::mem/c-string ::mem/pointer]
                                    ::mem/int]
   :image-get-blob                 ["vips_image_get_blob"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer]
                                    ::mem/int]
   :image-get-double               ["vips_image_get_double"
                                    [::mem/pointer ::mem/c-string ::mem/pointer]
                                    ::mem/int]
   :image-get-fields               ["vips_image_get_fields" [::mem/pointer] ::mem/pointer]
   :image-get-int                  ["vips_image_get_int"
                                    [::mem/pointer ::mem/c-string ::mem/pointer]
                                    ::mem/int]
   :image-get-string               ["vips_image_get_string"
                                    [::mem/pointer ::mem/c-string ::mem/pointer]
                                    ::mem/int]
   :image-get-type                 ["vips_image_get_type" [] ::g-type]
   :image-get-typeof               ["vips_image_get_typeof" [::mem/pointer ::mem/c-string] ::g-type]
   :image-get-width                ["vips_image_get_width" [::mem/pointer] ::mem/int]
   :image-has-alpha                ["vips_image_hasalpha" [::mem/pointer] ::mem/int]
   :image-new-from-buffer          ["vips_image_new_from_buffer"
                                    [::mem/pointer ::size-t ::mem/c-string ::mem/pointer]
                                    ::mem/pointer]
   :image-new-from-file            ["vips_image_new_from_file"
                                    [::mem/c-string ::mem/pointer]
                                    ::mem/pointer]
   :image-new-from-source          ["vips_image_new_from_source"
                                    [::mem/pointer ::mem/c-string ::mem/pointer]
                                    ::mem/pointer]
   :image-write-to-buffer          ["vips_image_write_to_buffer"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer ::mem/pointer]
                                    ::mem/int]
   :image-write-to-file            ["vips_image_write_to_file"
                                    [::mem/pointer ::mem/c-string ::mem/pointer]
                                    ::mem/int]
   :image-remove                   ["vips_image_remove" [::mem/pointer ::mem/c-string] ::mem/int]
   :image-set                      ["vips_image_set" [::mem/pointer ::mem/c-string ::mem/pointer] ::mem/void]
   :image-set-array-double         ["vips_image_set_array_double"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::mem/int]
                                    ::mem/void]
   :image-set-array-int            ["vips_image_set_array_int"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::mem/int]
                                    ::mem/void]
   :image-set-blob-copy            ["vips_image_set_blob_copy"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::size-t]
                                    ::mem/void]
   :image-set-double               ["vips_image_set_double" [::mem/pointer ::mem/c-string ::mem/double] ::mem/void]
   :image-set-int                  ["vips_image_set_int" [::mem/pointer ::mem/c-string ::mem/int] ::mem/void]
   :image-set-string               ["vips_image_set_string" [::mem/pointer ::mem/c-string ::mem/c-string] ::mem/void]
   :image-write-to-target          ["vips_image_write_to_target"
                                    [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer]
                                    ::mem/int]
   :operation-get-type             ["vips_operation_get_type" [] ::g-type]
   :operation-new                  ["vips_operation_new" [::mem/c-string] ::mem/pointer]
   :array-image-get-type           ["vips_array_image_get_type" [] ::g-type]
   :array-double-get-type          ["vips_array_double_get_type" [] ::g-type]
   :array-image-new                ["vips_array_image_new" [::mem/pointer ::mem/int] ::mem/pointer]
   :array-double-new               ["vips_array_double_new" [::mem/pointer ::mem/int] ::mem/pointer]
   :area-unref                     ["vips_area_unref" [::mem/pointer] ::mem/void]
   :object-get-description         ["vips_object_get_description" [::mem/pointer] ::mem/c-string]
   :object-get-arg-flags           ["vips_object_get_argument_flags" [::mem/pointer ::mem/c-string] ::mem/int]
   :object-get-arg-priority        ["vips_object_get_argument_priority" [::mem/pointer ::mem/c-string] ::mem/int]
   :object-unref-outputs           ["vips_object_unref_outputs" [::mem/pointer] ::mem/void]
   :cache-operation-build          ["vips_cache_operation_build" [::mem/pointer] ::mem/pointer]
   :vips-cache-set-max             ["vips_cache_set_max" [::mem/int] ::mem/void]
   :vips-cache-set-max-mem         ["vips_cache_set_max_mem" [::size-t] ::mem/void]
   :vips-cache-get-max             ["vips_cache_get_max" [] ::mem/int]
   :vips-cache-get-size            ["vips_cache_get_size" [] ::mem/int]
   :vips-cache-get-max-mem         ["vips_cache_get_max_mem" [] ::size-t]
   :vips-cache-get-max-files       ["vips_cache_get_max_files" [] ::mem/int]
   :vips-cache-set-max-files       ["vips_cache_set_max_files" [::mem/int] ::mem/void]
   :vips-tracked-get-mem           ["vips_tracked_get_mem" [] ::size-t]
   :vips-tracked-get-mem-highwater ["vips_tracked_get_mem_highwater" [] ::size-t]
   :vips-tracked-get-allocs        ["vips_tracked_get_allocs" [] ::mem/int]
   :vips-tracked-get-files         ["vips_tracked_get_files" [] ::mem/int]
   :source-custom-new              ["vips_source_custom_new" [] ::mem/pointer]
   :target-custom-new              ["vips_target_custom_new" [] ::mem/pointer]
   :vips-error-buffer              ["vips_error_buffer" [] ::mem/c-string]
   :vips-error-clear               ["vips_error_clear" [] ::mem/void]
   :vips-block-untrusted-set       ["vips_block_untrusted_set" [::mem/int] ::mem/void]
   :vips-init                      ["vips_init" [::mem/c-string] ::mem/int]
   :vips-shutdown                  ["vips_shutdown" [] ::mem/void]
   :vips-version                   ["vips_version" [::mem/int] ::mem/int]
   :vips-version-string            ["vips_version_string" [] ::mem/c-string]})

(defonce ^:private state* (atom nil))

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

(defn- set-block-untrusted-operations!
  [native state]
  ((:vips-block-untrusted-set native) (if state 1 0)))

(defn initialize-native-state
  [load-state]
  (let [native     (bind-symbols* (:resolve-symbol load-state))
        base-state (dissoc load-state :resolve-symbol)
        init-code  (int ((:vips-init native) "ol.vips"))
        _          (when-not (zero? init-code)
                     (throw-vips-error native
                                       "Failed to initialize libvips"
                                       {:exit-code init-code}))
        _          (set-block-untrusted-operations! native true)
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

(defn state
  []
  (ensure-initialized!))

(defn allow-untrusted-operations!
  []
  (let [current-state (ensure-initialized!)
        next-state    (assoc current-state :block-untrusted-operations? false)]
    (set-block-untrusted-operations! (:bindings current-state) false)
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

(defn version-string
  []
  (:version-string (ensure-initialized!)))

(defn operation-cache-settings
  []
  (let [native (bindings)]
    {:max       ((:vips-cache-get-max native))
     :size      ((:vips-cache-get-size native))
     :max-mem   ((:vips-cache-get-max-mem native))
     :max-files ((:vips-cache-get-max-files native))}))

(defn set-operation-cache-max!
  [max]
  (let [native (bindings)]
    ((:vips-cache-set-max native) max)
    (operation-cache-settings)))

(defn set-operation-cache-max-mem!
  [max-mem]
  (let [native (bindings)]
    ((:vips-cache-set-max-mem native) max-mem)
    (operation-cache-settings)))

(defn set-operation-cache-max-files!
  [max-files]
  (let [native (bindings)]
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

(defn render-option-value
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (boolean? value) (if value "true" "false")
    (sequential? value) (str/join " " (map render-option-value value))
    :else (str value)))

(defn render-option-string
  [opts]
  (when (seq opts)
    (str "["
         (->> opts
              (sort-by (comp str key))
              (map (fn [[k v]]
                     (str (name k) "=" (render-option-value v))))
              (str/join ","))
         "]")))

(defn append-options
  [value opts]
  (let [value         (str value)
        option-string (render-option-string opts)]
    (if-not option-string
      value
      (if (and (str/includes? value "[")
               (str/ends-with? value "]"))
        (str (subs value 0 (dec (count value)))
             ","
             (subs option-string 1))
        (str value option-string)))))

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

(deftype ImageHandle [ptr ^AtomicBoolean closed? keeper]
  PointerBacked
  (pointer [_] ptr)

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
  (let [stub      (mem/serialize callback callback-type arena)
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

(defn new-source-bridge
  [^InputStream stream]
  (let [arena       (Arena/ofShared)
        failure-ref (AtomicReference. nil)
        ptr         ((bindings :source-custom-new))]
    (when (mem/null? ptr)
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
                                  (mem/write-bytes (mem/reinterpret data requested) read-size chunk))
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
                       #(close-quietly stream)
                       (AtomicBoolean. false)))
      (catch Throwable t
        ((bindings :g-object-unref) ptr)
        (.close arena)
        (close-quietly stream)
        (throw t)))))

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
    (when (mem/null? ptr)
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
                                   (let [chunk (mem/read-bytes (mem/reinterpret data length) write-size)]
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
   (when-not (mem/null? ptr)
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

(defn type-name
  [gtype]
  ((bindings :g-type-name) gtype))

(defn type-fundamental
  [gtype]
  ((bindings :g-type-fundamental) gtype))

(defn with-gvalue
  [gtype f]
  (with-open [arena (mem/confined-arena)]
    (let [value (mem/alloc-instance ::g-value arena)]
      ((bindings :g-value-init) value gtype)
      (try
        (f value)
        (finally
          ((bindings :g-value-unset) value))))))

(defn open-image
  [source]
  (let [path  (str source)
        image ((bindings :image-new-from-file) path nil)]
    (when (mem/null? image)
      (throw-vips-error (bindings)
                        "Failed to open image"
                        {:source path}))
    (wrap-image image)))

(def ^:private byte-array-class
  (class (byte-array 0)))

(def ^:private missing-field-sentinel
  (Object.))

(def ^:private c-string-max-bytes
  65536)

(defn ->byte-array
  [value]
  (cond
    (instance? byte-array-class value) value
    (sequential? value) (byte-array (map byte value))
    :else (throw (ex-info "Expected image bytes"
                          {:value value}))))

(defn open-image-from-buffer
  ([source]
   (open-image-from-buffer source ""))
  ([source option-string]
   (let [data   (->byte-array source)
         arena  (Arena/ofShared)
         size   (alength ^bytes data)
         buffer (mem/alloc size 1 arena)
         image  (do
                  (mem/write-bytes buffer size data)
                  ((bindings :image-new-from-buffer) buffer size option-string nil))]
     (when (mem/null? image)
       (.close arena)
       (throw-vips-error (bindings)
                         "Failed to open image from buffer"
                         {:byte-count size}))
     (wrap-image image arena))))

(defn open-image-from-stream
  ([source]
   (open-image-from-stream source ""))
  ([source option-string]
   (let [stream (require-instance InputStream source "from-stream source")
         bridge (new-source-bridge stream)
         image  ((bindings :image-new-from-source) (pointer bridge) (or option-string "") nil)]
     (when (mem/null? image)
       (.close ^java.lang.AutoCloseable bridge)
       (throw-stream-error "Failed to open image from stream"
                           {}
                           (stream-failure-ref bridge)))
     (wrap-image image bridge))))

(defn write-image!
  [image sink]
  (let [path (str sink)
        code ((bindings :image-write-to-file) (pointer (image-handle image)) path nil)]
    (when-not (zero? code)
      (throw-vips-error (bindings)
                        "Failed to write image"
                        {:sink path}))
    image))

(defn write-image-to-buffer
  [image suffix]
  (with-open [arena (mem/confined-arena)]
    (let [buffer-ptr (mem/alloc-instance ::mem/pointer arena)
          size-ptr   (mem/alloc-instance ::size-t arena)
          code       ((bindings :image-write-to-buffer)
                      (pointer (image-handle image))
                      (str suffix)
                      buffer-ptr
                      size-ptr
                      nil)]
      (when-not (zero? code)
        (throw-vips-error (bindings)
                          "Failed to write image to buffer"
                          {:suffix suffix}))
      (let [output-ptr  (mem/read-address buffer-ptr)
            output-size (mem/read-long size-ptr)]
        (try
          (mem/read-bytes (mem/reinterpret output-ptr output-size) output-size)
          (finally
            ((bindings :g-free) output-ptr)))))))

(defn write-image-to-stream
  [image sink suffix]
  (let [stream (require-instance OutputStream sink "write-to-stream sink")]
    (with-open [^StreamBridge bridge (new-target-bridge stream)]
      (let [code ((bindings :image-write-to-target)
                  (pointer (image-handle image))
                  (str suffix)
                  (pointer bridge)
                  nil)]
        (when-not (zero? code)
          (throw-stream-error "Failed to write image to stream"
                              {:suffix (str suffix)}
                              (stream-failure-ref bridge)))
        image))))

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
  ((bindings :image-get-typeof) (pointer (image-handle image)) (str field-name)))

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
    (if (mem/null? raw-fields)
      []
      (let [slot-size  (mem/size-of ::mem/pointer)
            max-fields 1024
            fields-ptr (.reinterpret ^java.lang.foreign.MemorySegment raw-fields
                                     (* max-fields slot-size))]
        (try
          (loop [idx 0
                 acc []]
            (let [field-ptr (.getAtIndex ^java.lang.foreign.MemorySegment fields-ptr
                                         java.lang.foreign.ValueLayout/ADDRESS
                                         idx)]
              (if (mem/null? field-ptr)
                acc
                (recur (inc idx) (conj acc (read-c-string field-ptr))))))
          (finally
            ((bindings :g-strfreev) raw-fields)))))))

(defn image-field-as-string
  ([image field-name]
   (image-field-as-string image field-name missing-field-sentinel))
  ([image field-name not-found]
   (if-not (image-has-field? image field-name)
     (if (identical? not-found missing-field-sentinel) nil not-found)
     (with-open [arena (mem/confined-arena)]
       (let [out-ptr (mem/alloc-instance ::mem/pointer arena)
             code    ((bindings :image-get-as-string)
                      (pointer (image-handle image))
                      (str field-name)
                      out-ptr)]
         (when-not (zero? code)
           (throw-vips-error (bindings)
                             "Failed to read image metadata field as string"
                             {:field (str field-name)}))
         (let [value-ptr (mem/read-address out-ptr)]
           (try
             (read-c-string value-ptr)
             (finally
               ((bindings :g-free) value-ptr)))))))))

(defn image-int-field
  [image field-name]
  (with-open [arena (mem/confined-arena)]
    (let [out-ptr (mem/alloc-instance ::mem/int arena)
          code    ((bindings :image-get-int)
                   (pointer (image-handle image))
                   (str field-name)
                   out-ptr)]
      (when-not (zero? code)
        (throw-vips-error (bindings)
                          "Failed to read integer image metadata field"
                          {:field (str field-name)}))
      (mem/read-int out-ptr))))

(defn image-double-field
  [image field-name]
  (with-open [arena (mem/confined-arena)]
    (let [out-ptr (mem/alloc-instance ::mem/double arena)
          code    ((bindings :image-get-double)
                   (pointer (image-handle image))
                   (str field-name)
                   out-ptr)]
      (when-not (zero? code)
        (throw-vips-error (bindings)
                          "Failed to read double image metadata field"
                          {:field (str field-name)}))
      (mem/read-double out-ptr))))

(defn image-string-field
  [image field-name]
  (with-open [arena (mem/confined-arena)]
    (let [out-ptr (mem/alloc-instance ::mem/pointer arena)
          code    ((bindings :image-get-string)
                   (pointer (image-handle image))
                   (str field-name)
                   out-ptr)]
      (when-not (zero? code)
        (throw-vips-error (bindings)
                          "Failed to read string image metadata field"
                          {:field (str field-name)}))
      (read-c-string (mem/read-address out-ptr)))))

(defn image-array-int-field
  [image field-name]
  (with-open [arena (mem/confined-arena)]
    (let [out-ptr (mem/alloc-instance ::mem/pointer arena)
          n-ptr   (mem/alloc-instance ::mem/int arena)
          code    ((bindings :image-get-array-int)
                   (pointer (image-handle image))
                   (str field-name)
                   out-ptr
                   n-ptr)]
      (when-not (zero? code)
        (throw-vips-error (bindings)
                          "Failed to read integer-array image metadata field"
                          {:field (str field-name)}))
      (let [count     (mem/read-int n-ptr)
            data-ptr  (.reinterpret ^java.lang.foreign.MemorySegment
                       (mem/read-address out-ptr)
                                    (* count (mem/size-of ::mem/int)))
            slot-size (mem/size-of ::mem/int)]
        (mapv (fn [idx]
                (.get ^java.lang.foreign.MemorySegment
                 data-ptr
                      java.lang.foreign.ValueLayout/JAVA_INT
                      (long (* idx slot-size))))
              (range count))))))

(defn image-array-double-field
  [image field-name]
  (with-open [arena (mem/confined-arena)]
    (let [out-ptr (mem/alloc-instance ::mem/pointer arena)
          n-ptr   (mem/alloc-instance ::mem/int arena)
          code    ((bindings :image-get-array-double)
                   (pointer (image-handle image))
                   (str field-name)
                   out-ptr
                   n-ptr)]
      (when-not (zero? code)
        (throw-vips-error (bindings)
                          "Failed to read double-array image metadata field"
                          {:field (str field-name)}))
      (let [count     (mem/read-int n-ptr)
            data-ptr  (.reinterpret ^java.lang.foreign.MemorySegment
                       (mem/read-address out-ptr)
                                    (* count (mem/size-of ::mem/double)))
            slot-size (mem/size-of ::mem/double)]
        (mapv (fn [idx]
                (.get ^java.lang.foreign.MemorySegment
                 data-ptr
                      java.lang.foreign.ValueLayout/JAVA_DOUBLE
                      (long (* idx slot-size))))
              (range count))))))

(defn image-blob-field
  [image field-name]
  (with-open [arena (mem/confined-arena)]
    (let [out-ptr (mem/alloc-instance ::mem/pointer arena)
          len-ptr (mem/alloc-instance ::size-t arena)
          code    ((bindings :image-get-blob)
                   (pointer (image-handle image))
                   (str field-name)
                   out-ptr
                   len-ptr)]
      (when-not (zero? code)
        (throw-vips-error (bindings)
                          "Failed to read blob image metadata field"
                          {:field (str field-name)}))
      (let [data-ptr (mem/read-address out-ptr)
            length   (mem/read-long len-ptr)]
        (mem/read-bytes (mem/reinterpret data-ptr length) length)))))

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
    (string? value) :string
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
   (let [field-name (str field-name)
         type       (or type (infer-field-type value))
         image-ptr  (pointer (image-handle image))]
     (case type
       :int ((bindings :image-set-int) image-ptr field-name (int value))
       :double ((bindings :image-set-double) image-ptr field-name (double value))
       :string ((bindings :image-set-string) image-ptr field-name (str value))
       :blob (let [data (->byte-array value)]
               ((bindings :image-set-blob-copy) image-ptr field-name data (alength ^bytes data)))
       :array-int (let [values (vec value)
                        count  (count values)]
                    (with-open [arena (mem/confined-arena)]
                      (let [data (mem/alloc (* count (mem/size-of ::mem/int))
                                            (mem/align-of ::mem/int)
                                            arena)]
                        (doseq [[idx item] (map-indexed vector values)]
                          (mem/write-int data (* idx (mem/size-of ::mem/int)) (int item)))
                        ((bindings :image-set-array-int) image-ptr field-name data count))))
       :array-double (let [values (vec value)
                           count  (count values)]
                       (with-open [arena (mem/confined-arena)]
                         (let [data (mem/alloc (* count (mem/size-of ::mem/double))
                                               (mem/align-of ::mem/double)
                                               arena)]
                           (doseq [[idx item] (map-indexed vector values)]
                             (mem/write-double data (* idx (mem/size-of ::mem/double)) (double item)))
                           ((bindings :image-set-array-double) image-ptr field-name data count))))
       (throw (ex-info "Unsupported image metadata type"
                       {:field field-name
                        :type  type
                        :value value})))
     image)))

(defn image-dissoc-field!
  [image field-name]
  ((bindings :image-remove) (pointer (image-handle image)) (str field-name))
  image)
