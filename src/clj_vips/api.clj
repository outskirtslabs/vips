(ns clj-vips.api
  "Direct libvips C API bindings using coffi FFI.
   
   This namespace contains:
   - Library loading and initialization
   - Type aliases and constants (including GType constants)
   - Direct C function bindings (defcfn)
   - GValue and memory management functions"
  (:require
   [clojure.java.io :as io]
   [coffi.mem :as mem :refer [defalias]]
   [coffi.ffi :as ffi :refer [defcfn]]))

(let [arch (System/getProperty "os.arch")]
  (try
    (ffi/load-system-library "vips")
    (catch Throwable _
      (throw (ex-info (str "Architecture not supported: " arch)
                      {:arch arch})))))

;; -----------------------------------------------------------------------------
;;; Type Aliases and Constants

(defalias ::GType ::mem/long)

(let [struct-def
      [::mem/struct
       [[:g-value ::mem/long]
        [:data [::mem/array ::mem/long 2]]]]]

  (def gvalue-size
    "Size of GValue struct"
    (mem/size-of struct-def))

  (def gvalue-align
    "Alignment of GValue struct"
    (mem/align-of struct-def)))

;; GType constants - resolved at runtime using g_type_from_name
;; These are initialized when vips is loaded
(def ^:dynamic *g-type-int* nil)
(def ^:dynamic *g-type-double* nil)
(def ^:dynamic *g-type-string* nil)
(def ^:dynamic *g-type-boolean* nil)
(def ^:dynamic *g-type-long* nil)
(def ^:dynamic *g-type-object* nil)
(def ^:dynamic *g-type-uint* nil)
(def ^:dynamic *g-type-int64* nil)
(def ^:dynamic *g-type-uint64* nil)

(defn make-gvalue
  "Create a new GValue in the given arena (default: auto-arena)"
  ([] (make-gvalue (mem/auto-arena)))
  ([arena]
   (mem/alloc gvalue-size gvalue-align arena)))

;; init-gtypes! moved to after g-type-from-name definition

;; -----------------------------------------------------------------------------
;;; Core libvips Functions

(defcfn vips-init
  "Initialize libvips. Returns 0 on success."
  "vips_init" [::mem/c-string] ::mem/int)

(defcfn vips-shutdown
  "Shutdown libvips cleanly."
  "vips_shutdown" [] ::mem/void)

(defcfn vips-error-buffer
  "Get the current error message."
  "vips_error_buffer" [] ::mem/c-string)

(defcfn vips-version
  "Get libvips version component. flag 0=major, 1=minor, 2=micro."
  "vips_version" [::mem/int] ::mem/int)

(defcfn vips-version-string
  "Get the libvips version as a static string."
  "vips_version_string" [] ::mem/c-string)

 ;; Configuration functions
(defcfn vips-leak-set
  "Enable/disable leak checking."
  "vips_leak_set" [::mem/int] ::mem/void)

(defcfn vips-concurrency-set
  "Set the number of worker threads."
  "vips_concurrency_set" [::mem/int] ::mem/void)

(defcfn vips-concurrency-get
  "Get the number of worker threads."
  "vips_concurrency_get" [] ::mem/int)

(defcfn vips-cache-set-max
  "Set the maximum number of operations to cache."
  "vips_cache_set_max" [::mem/int] ::mem/void)

(defcfn vips-cache-get-max
  "Get the maximum number of operations to cache."
  "vips_cache_get_max" [] ::mem/int)

(defcfn vips-cache-set-max-mem
  "Set the maximum amount of memory to use for caching."
  "vips_cache_set_max_mem" [::mem/long] ::mem/void)

(defcfn vips-cache-get-max-mem
  "Get the maximum amount of memory to use for caching."
  "vips_cache_get_max_mem" [] ::mem/long)

(defcfn vips-cache-set-max-files
  "Set the maximum number of files to cache."
  "vips_cache_set_max_files" [::mem/int] ::mem/void)

(defcfn vips-cache-get-max-files
  "Get the maximum number of files to cache."
  "vips_cache_get_max_files" [] ::mem/int)

(defcfn vips-cache-set-trace
  "Enable/disable cache operation tracing."
  "vips_cache_set_trace" [::mem/int] ::mem/void)

(defcfn vips-cache-drop-all
  "Drop the whole operation cache."
  "vips_cache_drop_all" [] ::mem/void)

(defcfn vips-thread-shutdown
  "Clear the cache for the current thread."
  "vips_thread_shutdown" [] ::mem/void)

(defcfn image-new-memory
  "vips_image_new_memory() creates a new VipsImage which, when written to, will create a memory image."
  "vips_image_new_memory" [] ::mem/pointer)

(defcfn image-new-from-file
  "Load an image from a file. Returns VipsImage pointer or null on error."
  "vips_image_new_from_file" [::mem/c-string ::mem/pointer] ::mem/pointer)

(defcfn image-write-to-file
  "Write an image to a file. Returns 0 on success."
  "vips_image_write_to_file" [::mem/pointer ::mem/c-string ::mem/pointer] ::mem/int)

(defcfn g-object-unref
  "Unref n object"
  "g_object_unref" [::mem/pointer] ::mem/void)

(defcfn vips-operation-new
  "Return a new VipsOperation with the specified nickname. Useful for language bindings.
You'll need to set any arguments and build the operation before you can use it. See vips_call() for a higher-level way to make new operations."
  "vips_operation_new"
  [::mem/c-string] ::mem/pointer)

(defcfn vips-image-get-type
  "vips_image_get_type" [] ::GType)

(defcfn g-value-init
  "Initializes value with the default value of type"
  "g_value_init"
  [::mem/pointer ::GType] ::mem/pointer)

;; Filename and loader helper functions
(defcfn vips-filename-get-filename
  "Extract the main filename part from a vips filename."
  "vips_filename_get_filename" [::mem/pointer ::mem/c-string] ::mem/c-string)

(defcfn vips-filename-get-options
  "Extract the options part from a vips filename."
  "vips_filename_get_options" [::mem/pointer ::mem/c-string] ::mem/c-string)

(defcfn vips-foreign-find-load
  "Find a loader for a filename."
  "vips_foreign_find_load" [::mem/pointer ::mem/c-string] ::mem/c-string)

;; Operation calling functions
(defcfn vips-call
  "Call a vips operation by name with variable arguments."
  "vips_call" [::mem/c-string ::mem/pointer] ::mem/int)

;; -----------------------------------------------------------------------------
;; GObject and GValue Functions

(defcfn g-type-from-name
  "g_type_from_name"
  [::mem/c-string] ::GType)

(defcfn g-type-fundamental
  "g_type_fundamental"
  [::GType] ::GType)

(defcfn g-value-set-object
  "g_value_set_object"
  [::mem/pointer ::mem/pointer] ::mem/void)

(defcfn g-value-set-string
  "g_value_set_string"
  [::mem/pointer ::mem/c-string] ::mem/void)

(defcfn g-value-set-boolean
  "g_value_set_boolean"
  [::mem/pointer ::mem/int] ::mem/void)

(defcfn g-value-set-int
  "g_value_set_int"
  [::mem/pointer ::mem/int] ::mem/void)

(defcfn g-value-set-uint
  "g_value_set_uint"
  [::mem/pointer ::mem/int] ::mem/void)

(defcfn g-value-set-long
  "g_value_set_long"
  [::mem/pointer ::mem/long] ::mem/void)

(defcfn g-value-set-int64
  "g_value_set_int64"
  [::mem/pointer ::mem/long] ::mem/void)

(defcfn g-value-set-uint64
  "g_value_set_uint64"
  [::mem/pointer ::mem/long] ::mem/void)

(defcfn g-value-set-double
  "g_value_set_double"
  [::mem/pointer ::mem/double] ::mem/void)

(defcfn g-value-set-enum
  "g_value_set_enum"
  [::mem/pointer ::mem/int] ::mem/void)

(defcfn g-value-set-flags
  "g_value_set_flags"
  [::mem/pointer ::mem/int] ::mem/void)

(defcfn g-value-set-boxed
  "g_value_set_boxed"
  [::mem/pointer ::mem/pointer] ::mem/void)

(defcfn g-value-get-object
  "g_value_get_object"
  [::mem/pointer] ::mem/pointer)

(defcfn g-value-unset
  "g_value_unset"
  [::mem/pointer] ::mem/void)

(defcfn g-object-ref
  "g_object_ref"
  [::mem/pointer] ::mem/pointer)

(defcfn g-object-set-property
  "g_object_set_property"
  [::mem/pointer ::mem/c-string ::mem/pointer] ::mem/void)

(defcfn g-object-get-property
  "g_object_get_property"
  [::mem/pointer ::mem/c-string ::mem/pointer] ::mem/void)

(defcfn g-param-spec-get-name
  "g_param_spec_get_name"
  [::mem/pointer] ::mem/c-string)

(defn init-gtypes!
  "Initialize GType constants using g_type_from_name"
  []
  (alter-var-root #'*g-type-int* (constantly (g-type-from-name "gint")))
  (alter-var-root #'*g-type-double* (constantly (g-type-from-name "gdouble")))
  (alter-var-root #'*g-type-string* (constantly (g-type-from-name "gchararray")))
  (alter-var-root #'*g-type-boolean* (constantly (g-type-from-name "gboolean")))
  (alter-var-root #'*g-type-long* (constantly (g-type-from-name "glong")))
  (alter-var-root #'*g-type-object* (constantly (g-type-from-name "GObject")))
  (alter-var-root #'*g-type-uint* (constantly (g-type-from-name "guint")))
  (alter-var-root #'*g-type-int64* (constantly (g-type-from-name "gint64")))
  (alter-var-root #'*g-type-uint64* (constantly (g-type-from-name "guint64"))))

;; -----------------------------------------------------------------------------
;; Operation and Introspection Functions

(defcfn operation-new
  "vips_operation_new"
  [::mem/c-string] ::mem/pointer)

(defcfn operation-get-type
  "vips_operation_get_type"
  [] ::GType)

(defcfn image-get-type
  "vips_image_get_type" [] ::GType)

(defcfn cache-operation-build
  "vips_cache_operation_build"
  [::mem/pointer] ::mem/pointer)

(defcfn object-unref-outputs
  "vips_object_unref_outputs"
  [::mem/pointer] ::mem/void)

(defcfn object-get-argument-flags
  "vips_object_get_argument_flags"
  [::mem/pointer ::mem/c-string] ::mem/int)

(defcfn argument-map
  "vips_argument_map"
  [::mem/pointer
   [::ffi/fn [::mem/pointer ::mem/pointer ::mem/pointer ::mem/pointer ::mem/pointer ::mem/pointer] ::mem/pointer]
   ::mem/pointer
   ::mem/pointer] ::mem/pointer)

(defcfn type-map-all
  "vips_type_map_all" [::GType
                       [::ffi/fn [::GType ::mem/pointer] ::mem/pointer]
                       ::mem/pointer] ::mem/pointer)

(defcfn nickname-find
  "vips_nickname_find" [::GType] ::mem/c-string)

(defcfn error-exit
  "vips_error_exit" [::mem/c-string] ::mem/void)
