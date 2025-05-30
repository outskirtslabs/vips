(ns user
  (:require
   [clj-vips.api :as api]
   [clojure.java.io :as io]
   [coffi.mem :as mem :refer [defalias]]
   [coffi.ffi :as ffi :refer [defcfn]]))

(ffi/load-system-library "vips")

(defalias ::GType ::mem/long)

(let [struct-def
      [::mem/struct
       [[:g-value ::mem/long]
        [:data [::mem/array ::mem/long 2]]]]]

  (def gvalue-size
    (mem/size-of struct-def))

  (def gvalue-align
    (mem/align-of struct-def)))

(defn make-gvalue
  ([] (make-gvalue (mem/auto-arena)))
  ([arena]
   (mem/alloc gvalue-size gvalue-align arena)))

(defcfn type-map-all
  "vips_type_map_all" [::GType
                       [::ffi/fn [::GType ::mem/pointer] ::mem/pointer]
                       ::mem/pointer] ::mem/pointer)

(defcfn nickname-find
  "vips_nickname_find" [::GType] ::mem/c-string)

(defcfn operation-get-type
  "vips_operation_get_type"
  [] ::GType)

(defcfn image-get-type
  "vips_image_get_type" [] ::GType)

(defcfn vips-init
  "Initialize libvips. Returns 0 on success."
  "vips_init" [::mem/c-string] ::mem/int)

(defcfn vips-shutdown
  "Shutdown libvips cleanly."
  "vips_shutdown" [] ::mem/void)

(defcfn operation-new
  "vips_operation_new"
  [::mem/c-string] ::mem/pointer)

(defcfn g-value-set-object
  "g_value_set_object"
  [::mem/pointer ::mem/pointer] ::mem/void)

(defcfn g-value-init
  "g_value_init"
  [::mem/pointer ::GType] ::mem/pointer)

(defcfn g-object-set-property
  "g_object_set_property"
  [::mem/pointer ::mem/c-string ::mem/pointer] ::mem/void)

(defcfn g-object-get-property
  "g_object_get_property"
  [::mem/pointer ::mem/c-string ::mem/pointer] ::mem/void)

(defcfn g-value-get-object
  "g_value_get_object"
  [::mem/pointer] ::mem/pointer)

(defcfn g-value-unset
  "g_value_unset"
  [::mem/pointer] ::mem/void)

(defcfn g-object-ref
  "g_object_ref"
  [::mem/pointer] ::mem/pointer)

(defcfn g-object-unset
  "g_value_unset"
  [::mem/pointer] ::mem/void)

(defcfn cache-operation-build
  "vips_cache_operation_build"
  [::mem/pointer] ::mem/pointer)

(defcfn object-unref-outputs
  "vips_object_unref_outputs"
  [::mem/pointer] ::mem/void)

(defcfn error-exit
  "vips_error_exit" [::mem/c-string] ::mem/void)

(defcfn argument-map
  "vips_argument_map"
  [::mem/pointer
   [::ffi/fn [::mem/pointer ::mem/pointer ::mem/pointer ::mem/pointer ::mem/pointer ::mem/pointer] ::mem/pointer]
   ::mem/pointer
   ::mem/pointer] ::mem/pointer)

(defcfn g-param-spec-get-name
  "g_param_spec_get_name"
  [::mem/pointer] ::mem/c-string)

(defcfn object-get-argument-flags
  "vips_object_get_argument_flags"
  [::mem/pointer ::mem/c-string] ::mem/int)

;; Commenting out for now - function might not exist
#_(defcfn g-param-spec-get-value-type
    "g_param_spec_get_value_type"
    [::mem/pointer] ::GType)

(comment
  (clojure.repl.deps/sync-deps)
  ;;
  )

(defn autorotate [path]
  (let [im (api/image-new-from-file path nil)
        op (operation-new "invert")
        g-value (make-gvalue)]
    (g-value-init g-value (image-get-type))
    (g-value-set-object g-value im)
    (g-object-set-property op "in" g-value)
    (g-value-unset g-value)
    (api/g-object-unref im)
    (let [new-op (cache-operation-build op)]
      (when (mem/null? new-op)
        (api/g-object-unref op)
        (error-exit mem/null))
      (api/g-object-unref op)
      (g-value-init g-value (image-get-type))
      (g-object-get-property new-op "out" g-value)
      (let [out (g-value-get-object g-value)]
        (when (mem/null? out)
          (error-exit "output null"))
        (g-object-ref out)
        (g-value-unset g-value)
        (object-unref-outputs new-op)
        (api/g-object-unref new-op)
        (when (mem/null? (api/image-write-to-file out "output.png" mem/null))
          (error-exit mem/null))
        (api/g-object-unref out)))))

(defn set-image-argument
  "Set an image argument on an operation"
  [operation arg-name image]
  (let [g-value (make-gvalue)]
    (g-value-init g-value (image-get-type))
    (g-value-set-object g-value image)
    (g-object-set-property operation arg-name g-value)
    (g-value-unset g-value)))

(defn get-image-result
  "Get an image result from an operation"
  [operation arg-name]
  (let [g-value (make-gvalue)]
    (g-value-init g-value (image-get-type))
    (g-object-get-property operation arg-name g-value)
    (let [result (g-value-get-object g-value)]
      (when-not (mem/null? result)
        (g-object-ref result))
      (g-value-unset g-value)
      result)))

(defn call-operation
  "Call a vips operation with given  arguments and options.

  - operation-name is the name of the vips operation (e.g. 'autorot').
  - Arguments are a vector of positional required arguments for the operation.
  - Options are a map of keyword arguments where the keys are option names (original casing!)

  Example:

  (let [out (call-operation \"gravity\"  [im :VIPS_COMPASS_DIRECTION_CENTRE 650 500] {:extend :VIPS_EXTEND_COPY})]
    ;; ... write out to file
  )"
  [operation-name args opts]
  ;; Simple implementation for image-to-image operations like invert
  (let [op (operation-new operation-name)
        input-image (first args)]
    (try
      ;; Set the input image
      (set-image-argument op "in" input-image)

      ;; Build the operation  
      (let [built-op (cache-operation-build op)]
        (when (mem/null? built-op)
          (api/g-object-unref op)
          (throw (ex-info "Operation build failed" {:operation operation-name})))

        (api/g-object-unref op)

        ;; Get the output image
        (let [result (get-image-result built-op "out")]
          (object-unref-outputs built-op)
          (api/g-object-unref built-op)
          result))

      (catch Exception e
        (api/g-object-unref op)
        (throw e)))))

(defn arg-discovery-callback
  "Callback for vips_argument_map to discover operation arguments"
  [object pspec arg-class arg-instance user-data _]
  (let [arg-name (g-param-spec-get-name pspec)
        flags (object-get-argument-flags object arg-name)]
    (printf "Argument: %-12s flags=0x%02x input=%s output=%s required=%s%n"
            arg-name
            flags
            (not= 0 (bit-and flags 16)) ; VIPS_ARGUMENT_INPUT = 16
            (not= 0 (bit-and flags 32)) ; VIPS_ARGUMENT_OUTPUT = 32  
            (not= 0 (bit-and flags 1)))) ; VIPS_ARGUMENT_REQUIRED = 1
  mem/null)

(defn discover-operation-arguments
  "Discover all arguments for a given operation"
  [operation]
  (argument-map operation arg-discovery-callback mem/null mem/null))

(defn op-list-callback [a g-type-int user-data]
  ;; Extract the operation name and add it to our collection
  (let [operation-name (nickname-find g-type-int)]
    (when operation-name
      (vswap! a conj operation-name)
      (println "Found operation:" operation-name)))
  mem/null)

(defn operation-list []
  (let [callback op-list-callback
        data (volatile! [])]
    (type-map-all (operation-get-type)
                  (partial callback data)
                  mem/null)
    (println "Got all")
    (prn (sort @data))))

(defn try-operation-list []
  (try
    (vips-init "clj-vips")
    (operation-list)
    (finally
      (vips-shutdown))))
(defn try-autorotate []
  (let [orig-path "test/clj_vips/fixtures/clojure.png"]
    (try
      (vips-init "clj-vips")
      (autorotate orig-path)
      (finally
        (vips-shutdown)))))

(defn -main [& args]
  (try
    (vips-init "clj-vips")
    (let [resize-op (operation-new "resize")]
      (println "Discovering arguments for 'resize' operation:")
      (discover-operation-arguments resize-op)
      (api/g-object-unref resize-op))
    (finally
      (vips-shutdown))))
