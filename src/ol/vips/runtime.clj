(ns ol.vips.runtime
  (:require
   [clojure.string :as str]
   [coffi.ffi :as ffi]
   [coffi.layout :as layout]
   [coffi.mem :as mem]
   [ol.vips.native.loader :as loader])
  (:import
   [java.io File]
   [java.lang.foreign Arena SymbolLookup]
   [java.nio.file Path]
   [java.util.concurrent.atomic AtomicBoolean]))

(set! *warn-on-reflection* true)

(mem/defalias ::g-type ::mem/long)
(mem/defalias ::size-t ::mem/long)

(mem/defalias ::g-value
  (layout/with-c-layout
    [::mem/struct
     [[:g-type ::mem/long]
      [:data [::mem/array ::mem/long 2]]]]))

(declare bindings)

(defprotocol PointerBacked
  (pointer ^java.lang.foreign.MemorySegment [this]))

(declare image-handle)

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

(deftype ImageHandle [ptr ^AtomicBoolean closed?]
  PointerBacked
  (pointer [_] ptr)

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      ((bindings :g-object-unref) ptr)))

  Object
  (toString [_]
    (str "#<ol.vips.runtime.ImageHandle " ptr ">")))

(def ^:private native-symbol-specs
  {:g-free                  ["g_free" [::mem/pointer] ::mem/void]
   :g-object-ref            ["g_object_ref" [::mem/pointer] ::mem/pointer]
   :g-object-unref          ["g_object_unref" [::mem/pointer] ::mem/void]
   :g-object-get-property   ["g_object_get_property"
                             [::mem/pointer ::mem/c-string ::mem/pointer]
                             ::mem/void]
   :g-object-set-property   ["g_object_set_property"
                             [::mem/pointer ::mem/c-string ::mem/pointer]
                             ::mem/void]
   :g-type-children         ["g_type_children" [::g-type ::mem/pointer] ::mem/pointer]
   :g-type-class-ref        ["g_type_class_ref" [::g-type] ::mem/pointer]
   :g-type-class-unref      ["g_type_class_unref" [::mem/pointer] ::mem/void]
   :g-type-fundamental      ["g_type_fundamental" [::g-type] ::g-type]
   :g-type-from-name        ["g_type_from_name" [::mem/c-string] ::g-type]
   :g-type-name             ["g_type_name" [::g-type] ::mem/c-string]
   :param-spec-get-blurb    ["g_param_spec_get_blurb" [::mem/pointer] ::mem/c-string]
   :param-spec-get-name     ["g_param_spec_get_name" [::mem/pointer] ::mem/c-string]
   :nickname-find           ["vips_nickname_find" [::g-type] ::mem/c-string]
   :argument-map            ["vips_argument_map"
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
   :type-map-all            ["vips_type_map_all"
                             [::g-type
                              [::ffi/fn [::g-type ::mem/pointer] ::mem/pointer]
                              ::mem/pointer]
                             ::mem/pointer]
   :g-value-get-boolean     ["g_value_get_boolean" [::mem/pointer] ::mem/int]
   :g-value-get-double      ["g_value_get_double" [::mem/pointer] ::mem/double]
   :g-value-get-enum        ["g_value_get_enum" [::mem/pointer] ::mem/int]
   :g-value-get-flags       ["g_value_get_flags" [::mem/pointer] ::mem/int]
   :g-value-get-int         ["g_value_get_int" [::mem/pointer] ::mem/int]
   :g-value-get-int64       ["g_value_get_int64" [::mem/pointer] ::mem/long]
   :g-value-get-object      ["g_value_get_object" [::mem/pointer] ::mem/pointer]
   :g-value-get-string      ["g_value_get_string" [::mem/pointer] ::mem/c-string]
   :g-value-get-uint        ["g_value_get_uint" [::mem/pointer] ::mem/int]
   :g-value-get-uint64      ["g_value_get_uint64" [::mem/pointer] ::mem/long]
   :g-value-init            ["g_value_init" [::mem/pointer ::g-type] ::mem/pointer]
   :g-value-set-boolean     ["g_value_set_boolean" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-boxed       ["g_value_set_boxed" [::mem/pointer ::mem/pointer] ::mem/void]
   :g-value-set-double      ["g_value_set_double" [::mem/pointer ::mem/double] ::mem/void]
   :g-value-set-enum        ["g_value_set_enum" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-flags       ["g_value_set_flags" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-int         ["g_value_set_int" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-int64       ["g_value_set_int64" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-set-long        ["g_value_set_long" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-set-object      ["g_value_set_object" [::mem/pointer ::mem/pointer] ::mem/void]
   :g-value-set-string      ["g_value_set_string" [::mem/pointer ::mem/c-string] ::mem/void]
   :g-value-set-uint        ["g_value_set_uint" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-uint64      ["g_value_set_uint64" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-unset           ["g_value_unset" [::mem/pointer] ::mem/void]
   :image-get-height        ["vips_image_get_height" [::mem/pointer] ::mem/int]
   :image-get-bands         ["vips_image_get_bands" [::mem/pointer] ::mem/int]
   :image-get-type          ["vips_image_get_type" [] ::g-type]
   :image-get-width         ["vips_image_get_width" [::mem/pointer] ::mem/int]
   :image-has-alpha         ["vips_image_hasalpha" [::mem/pointer] ::mem/int]
   :image-new-from-buffer   ["vips_image_new_from_buffer"
                             [::mem/pointer ::size-t ::mem/c-string ::mem/pointer]
                             ::mem/pointer]
   :image-new-from-file     ["vips_image_new_from_file"
                             [::mem/c-string ::mem/pointer]
                             ::mem/pointer]
   :image-write-to-buffer   ["vips_image_write_to_buffer"
                             [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer ::mem/pointer]
                             ::mem/int]
   :image-write-to-file     ["vips_image_write_to_file"
                             [::mem/pointer ::mem/c-string ::mem/pointer]
                             ::mem/int]
   :operation-get-type      ["vips_operation_get_type" [] ::g-type]
   :operation-new           ["vips_operation_new" [::mem/c-string] ::mem/pointer]
   :array-image-get-type    ["vips_array_image_get_type" [] ::g-type]
   :array-image-new         ["vips_array_image_new" [::mem/pointer ::mem/int] ::mem/pointer]
   :area-unref              ["vips_area_unref" [::mem/pointer] ::mem/void]
   :object-get-description  ["vips_object_get_description" [::mem/pointer] ::mem/c-string]
   :object-get-arg-flags    ["vips_object_get_argument_flags" [::mem/pointer ::mem/c-string] ::mem/int]
   :object-get-arg-priority ["vips_object_get_argument_priority" [::mem/pointer ::mem/c-string] ::mem/int]
   :object-unref-outputs    ["vips_object_unref_outputs" [::mem/pointer] ::mem/void]
   :cache-operation-build   ["vips_cache_operation_build" [::mem/pointer] ::mem/pointer]
   :vips-error-buffer       ["vips_error_buffer" [] ::mem/c-string]
   :vips-error-clear        ["vips_error_clear" [] ::mem/void]
   :vips-init               ["vips_init" [::mem/c-string] ::mem/int]
   :vips-shutdown           ["vips_shutdown" [] ::mem/void]
   :vips-version            ["vips_version" [::mem/int] ::mem/int]
   :vips-version-string     ["vips_version_string" [] ::mem/c-string]})

(defonce ^:private state* (atom nil))

(defn- path->lookup
  ^SymbolLookup [^String library-path ^Arena arena]
  (SymbolLookup/libraryLookup (Path/of library-path (make-array String 0))
                              arena))

(defn- library-lookup
  [library-paths]
  (let [arena   (Arena/ofShared)
        lookups (mapv #(path->lookup % arena) library-paths)]
    {:arena  arena
     :lookup (reduce (fn [^SymbolLookup acc ^SymbolLookup lookup]
                       (.or acc lookup))
                     lookups)}))

(defn- lookup-symbol
  [^SymbolLookup lookup symbol-name]
  (or (.orElse (.find lookup symbol-name) nil)
      (throw (ex-info "Native symbol not found in loaded ol.vips libraries"
                      {:symbol symbol-name}))))

(defn- bind-symbols
  [^SymbolLookup lookup]
  (reduce-kv
   (fn [native k [symbol-name arg-types return-type]]
     (assoc native
            k
            (ffi/cfn (lookup-symbol lookup symbol-name)
                     arg-types
                     return-type)))
   {}
   native-symbol-specs))

(defn- last-error-message
  [native]
  (some-> ((:vips-error-buffer native)) not-empty))

(defn- clear-error!
  [native]
  ((:vips-error-clear native)))

(defn- preload-library-paths
  []
  (when-let [value (some-> (System/getProperty "ol.vips.native.preload")
                           str/trim
                           not-empty)]
    (->> (str/split value (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- throw-vips-error
  [native message data]
  (let [error-message (last-error-message native)]
    (clear-error! native)
    (throw (ex-info message
                    (cond-> data
                      error-message (assoc :vips/error error-message))))))

(defn- build-gtypes
  [native]
  {:boolean     ((:g-type-from-name native) "gboolean")
   :boxed       ((:g-type-from-name native) "GBoxed")
   :double      ((:g-type-from-name native) "gdouble")
   :enum        ((:g-type-from-name native) "GEnum")
   :flags       ((:g-type-from-name native) "GFlags")
   :image       ((:image-get-type native))
   :int         ((:g-type-from-name native) "gint")
   :int64       ((:g-type-from-name native) "gint64")
   :long        ((:g-type-from-name native) "glong")
   :object      ((:g-type-from-name native) "GObject")
   :operation   ((:operation-get-type native))
   :string      ((:g-type-from-name native) "gchararray")
   :uint        ((:g-type-from-name native) "guint")
   :uint64      ((:g-type-from-name native) "guint64")
   :array-image ((:array-image-get-type native))})

(defn- wrap-image
  [ptr]
  (when-not (mem/null? ptr)
    (ImageHandle. ptr (AtomicBoolean. false))))

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

(defn ensure-initialized!
  []
  (or @state*
      (locking state*
        (or @state*
            (let [manifest               (loader/read-manifest)
                  extracted              (loader/extract-libraries! (loader/default-cache-root) manifest)
                  load-paths             (vec (concat (preload-library-paths) (:library-paths extracted)))
                  _                      (doseq [path load-paths]
                                           (ffi/load-library path))
                  {:keys [arena lookup]} (library-lookup load-paths)
                  exposed                (loader/expose-paths! extracted)
                  native                 (bind-symbols lookup)
                  init-code              ((:vips-init native) "ol.vips")
                  _                      (when-not (zero? init-code)
                                           (throw-vips-error native
                                                             "Failed to initialize libvips"
                                                             {:exit-code init-code}))
                  version                ((:vips-version-string native))
                  state                  (merge exposed
                                                {:bindings             native
                                                 :gtypes               (build-gtypes native)
                                                 :lookup               lookup
                                                 :lookup-arena         arena
                                                 :manifest             manifest
                                                 :primary-library-path (first (:library-paths extracted))
                                                 :version-string       version})]
              (reset! state* state)
              state)))))

(defn state
  []
  (ensure-initialized!))

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

(defn- ->byte-array
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
   (let [data (->byte-array source)]
     (with-open [arena (mem/confined-arena)]
       (let [size   (alength ^bytes data)
             buffer (mem/alloc size 1 arena)
             image  (do
                      (mem/write-bytes buffer size data)
                      ((bindings :image-new-from-buffer) buffer size option-string nil))]
         (when (mem/null? image)
           (throw-vips-error (bindings)
                             "Failed to open image from buffer"
                             {:byte-count size}))
         (wrap-image image))))))

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
          (mem/read-bytes (mem/reinterpret output-ptr output-size) (int output-size))
          (finally
            ((bindings :g-free) output-ptr)))))))

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
