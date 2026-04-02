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

(mem/defalias ::g-value
  (layout/with-c-layout
    [::mem/struct
     [[:g-type ::mem/long]
      [:data [::mem/array ::mem/long 2]]]]))

(declare bindings)

(defprotocol PointerBacked
  (pointer ^java.lang.foreign.MemorySegment [this]))

(deftype ImageHandle [ptr ^AtomicBoolean closed?]
  PointerBacked
  (pointer [_] ptr)

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      ((:g-object-unref (bindings)) ptr)))

  Object
  (toString [_]
    (str "#<ol.vips.runtime.ImageHandle " ptr ">")))

(def ^:private native-symbol-specs
  {:g-object-ref            ["g_object_ref" [::mem/pointer] ::mem/pointer]
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
   :image-get-type          ["vips_image_get_type" [] ::g-type]
   :image-get-width         ["vips_image_get_width" [::mem/pointer] ::mem/int]
   :image-has-alpha         ["vips_image_hasalpha" [::mem/pointer] ::mem/int]
   :image-new-from-file     ["vips_image_new_from_file"
                             [::mem/c-string ::mem/pointer]
                             ::mem/pointer]
   :image-write-to-file     ["vips_image_write_to_file"
                             [::mem/pointer ::mem/c-string ::mem/pointer]
                             ::mem/int]
   :operation-get-type      ["vips_operation_get_type" [] ::g-type]
   :operation-new           ["vips_operation_new" [::mem/c-string] ::mem/pointer]
   :array-image-get-type    ["vips_array_image_get_type" [] ::g-type]
   :array-image-new         ["vips_array_image_new" [::mem/pointer ::mem/int] ::mem/pointer]
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
   (fn [bindings k [symbol-name arg-types return-type]]
     (assoc bindings
            k
            (ffi/cfn (lookup-symbol lookup symbol-name)
                     arg-types
                     return-type)))
   {}
   native-symbol-specs))

(defn- last-error-message
  [bindings]
  (some-> ((:vips-error-buffer bindings)) not-empty))

(defn- clear-error!
  [bindings]
  ((:vips-error-clear bindings)))

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
  [bindings message data]
  (let [error-message (last-error-message bindings)]
    (clear-error! bindings)
    (throw (ex-info message
                    (cond-> data
                      error-message (assoc :vips/error error-message))))))

(defn- build-gtypes
  [bindings]
  {:boolean     ((:g-type-from-name bindings) "gboolean")
   :boxed       ((:g-type-from-name bindings) "GBoxed")
   :double      ((:g-type-from-name bindings) "gdouble")
   :enum        ((:g-type-from-name bindings) "GEnum")
   :flags       ((:g-type-from-name bindings) "GFlags")
   :image       ((:image-get-type bindings))
   :int         ((:g-type-from-name bindings) "gint")
   :int64       ((:g-type-from-name bindings) "gint64")
   :long        ((:g-type-from-name bindings) "glong")
   :object      ((:g-type-from-name bindings) "GObject")
   :operation   ((:operation-get-type bindings))
   :string      ((:g-type-from-name bindings) "gchararray")
   :uint        ((:g-type-from-name bindings) "guint")
   :uint64      ((:g-type-from-name bindings) "guint64")
   :array-image ((:array-image-get-type bindings))})

(defn- wrap-image
  [ptr]
  (when-not (mem/null? ptr)
    (ImageHandle. ptr (AtomicBoolean. false))))

(defn adopt-image
  [ptr]
  (wrap-image ptr))

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
                  bindings               (bind-symbols lookup)
                  init-code              ((:vips-init bindings) "ol.vips")
                  _                      (when-not (zero? init-code)
                                           (throw-vips-error bindings
                                                             "Failed to initialize libvips"
                                                             {:exit-code init-code}))
                  version                ((:vips-version-string bindings))
                  state                  (merge exposed
                                                {:bindings             bindings
                                                 :gtypes               (build-gtypes bindings)
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
  []
  (:bindings (ensure-initialized!)))

(defn gtypes
  []
  (:gtypes (ensure-initialized!)))

(defn version-string
  []
  (:version-string (ensure-initialized!)))

(defn type-name
  [gtype]
  ((:g-type-name (bindings)) gtype))

(defn type-fundamental
  [gtype]
  ((:g-type-fundamental (bindings)) gtype))

(defn with-gvalue
  [gtype f]
  (let [bindings (bindings)]
    (with-open [arena (mem/confined-arena)]
      (let [value (mem/alloc-instance ::g-value arena)]
        ((:g-value-init bindings) value gtype)
        (try
          (f value)
          (finally
            ((:g-value-unset bindings) value)))))))

(defn open-image
  [source]
  (let [bindings (bindings)
        path     (str source)
        image    ((:image-new-from-file bindings) path nil)]
    (when (mem/null? image)
      (throw-vips-error bindings
                        "Failed to open image"
                        {:source path}))
    (wrap-image image)))

(defn write-image!
  [image sink]
  (let [bindings (bindings)
        path     (str sink)
        code     ((:image-write-to-file bindings) (pointer image) path nil)]
    (when-not (zero? code)
      (throw-vips-error bindings
                        "Failed to write image"
                        {:sink path}))
    image))

(defn image-info
  [image]
  (let [bindings (bindings)
        ptr      (pointer image)]
    {:width      ((:image-get-width bindings) ptr)
     :height     ((:image-get-height bindings) ptr)
     :has-alpha? (not (zero? ((:image-has-alpha bindings) ptr)))}))
