(ns clj-vips.api
  "These function map directly to libvips' C API."
  (:require
   [clojure.java.io :as io]
   [coffi.mem :as mem]
   [coffi.ffi :as ffi :refer [defcfn]]))

(let [arch (System/getProperty "os.arch")]
  (try
    (ffi/load-system-library "vips")
    (catch Throwable _
      (throw (ex-info (str "Architecture not supported: " arch)
                      {:arch arch})))))

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

(defcfn g_object_unref
  "Unref n object"
  "g_object_unref" [::mem/pointer] ::mem/void)
