(ns clj-vips.api.operation
  "Higher-level operation calling and introspection functions.
   
   This namespace provides:
   - Operation argument discovery and introspection
   - Generic call-operation function with proper GType handling
   - GType to setter function mapping
   - GValue management utilities"
  (:require
   [clj-vips.api :as api]
   [coffi.mem :as mem]))

;; -----------------------------------------------------------------------------
;; Type System and GValue Management

(defn g-param-spec-value-type
  "Get the GType from a GParamSpec - detect different types based on argument name.
   This is a workaround until we can properly read GParamSpec memory structure."
  [pspec]
  (let [arg-name (api/g-param-spec-get-name pspec)]
    ;; For now, provide type hints based on common argument names
    (cond
      (or (= arg-name "scale") (= arg-name "vscale") (= arg-name "hscale"))
      (api/g-type-from-name "gdouble")

      (or (= arg-name "width") (= arg-name "height") (= arg-name "size"))
      (api/g-type-from-name "gint")

      (or (= arg-name "filename") (= arg-name "nickname") (= arg-name "description")
          (= arg-name "import-profile") (= arg-name "export-profile"))
      (api/g-type-from-name "gchararray")

      (or (= arg-name "no-rotate") (= arg-name "linear") (= arg-name "auto-rotate"))
      (api/g-type-from-name "gboolean")

      (= arg-name "crop")
      (api/g-type-from-name "GEnum")

      (or (= arg-name "in") (= arg-name "out"))
      (api/g-type-from-name "GObject")

      :else
      (api/g-type-from-name "GObject")))) ; Default to object type

(defn gtype->setter
  "Map a GType to the appropriate g_value_set_* function"
  [gtype]
  (cond
    (= gtype api/*g-type-object*) api/g-value-set-object
    (= gtype api/*g-type-string*) api/g-value-set-string
    (= gtype api/*g-type-boolean*) api/g-value-set-boolean
    (= gtype api/*g-type-int*) api/g-value-set-int
    (= gtype api/*g-type-uint*) api/g-value-set-uint
    (= gtype api/*g-type-long*) api/g-value-set-long
    (= gtype api/*g-type-int64*) api/g-value-set-int64
    (= gtype api/*g-type-uint64*) api/g-value-set-uint64
    (= gtype api/*g-type-double*) api/g-value-set-double
    ;; Check if it's an enum type by comparing the fundamental type
    (= (api/g-type-fundamental gtype) api/*g-type-enum*) api/g-value-set-enum
    :else
    (throw (ex-info "Unsupported GType" {:gtype gtype}))))

 ;; -----------------------------------------------------------------------------
;; Enum Handling

(def ^:private enum-keyword-mappings
  "Mapping of namespaced keywords to enum values"
  {:interesting/none 0
   :interesting/centre 1
   :interesting/entropy 2
   :interesting/attention 3
   :interesting/low 4
   :interesting/high 5
   :interesting/all 6})

(defn keyword->enum-value
  "Convert a namespaced keyword to its corresponding enum integer value"
  [keyword]
  (if-let [value (get enum-keyword-mappings keyword)]
    value
    (throw (ex-info "Unknown enum keyword" {:keyword keyword}))))

(defn set-gvalue
  "Set a GValue with the appropriate setter function and value conversion"
  [value-name g-value setter-fn value]
  (cond
    (= setter-fn api/g-value-set-string)
    (if (string? value)
      (setter-fn g-value value)
      (throw (ex-info "Attempt to set a string value which is not a string"
                      {:arg-name value-name :value value :type (type value)})))

    (= setter-fn api/g-value-set-boolean)
    (setter-fn g-value (if value 1 0)) ; GLib booleans are ints

    (= setter-fn api/g-value-set-enum)
    (let [enum-value (if (keyword? value)
                       (keyword->enum-value value)
                       value)]
      (setter-fn g-value enum-value))

    :else
    (setter-fn g-value value)))

;; -----------------------------------------------------------------------------
;; Operation Argument Management

(defn set-argument
  "Set an argument on an operation using operation introspection for correct GType"
  [operation {arg-name :name expected-gtype :gtype :as arg-info} value]
  (let [setter-fn (gtype->setter expected-gtype)
        g-value (api/make-gvalue)]
    (if (= expected-gtype api/*g-type-object*)
      (api/g-value-init g-value (api/image-get-type)) ; For objects, use specific image type
      (api/g-value-init g-value expected-gtype)) ; For primitives, use expected type
    (set-gvalue arg-name g-value setter-fn value)
    (api/g-object-set-property operation arg-name g-value)
    (api/g-value-unset g-value)))

(defn get-image-result
  "Get an image result from an operation"
  [operation arg-name]
  (let [g-value (api/make-gvalue)]
    (api/g-value-init g-value (api/image-get-type))
    (api/g-object-get-property operation arg-name g-value)
    (let [result (api/g-value-get-object g-value)]
      (when-not (mem/null? result)
        (api/g-object-ref result))
      (api/g-value-unset g-value)
      result)))

;; -----------------------------------------------------------------------------
;; Operation Introspection

(defn arg-discovery-callback
  "Callback for vips_argument_map to discover operation arguments"
  [args-volatile object pspec arg-class arg-instance user-data _]
  (let [arg-name (api/g-param-spec-get-name pspec)
        flags (api/object-get-argument-flags object arg-name)
        gtype (g-param-spec-value-type pspec)
        arg-info {:name arg-name
                  :flags flags
                  :gtype gtype
                  :input? (not= 0 (bit-and flags 16))
                  :output? (not= 0 (bit-and flags 32))
                  :required? (not= 0 (bit-and flags 1))}]
    (vswap! args-volatile conj arg-info))
  mem/null)

(defn discover-operation-arguments
  "Discover all arguments for a given operation. Returns vector of argument info maps."
  [operation]
  (let [args-info (volatile! [])]
    (api/argument-map operation
                      (partial arg-discovery-callback args-info)
                      mem/null mem/null)
    @args-info))

;; -----------------------------------------------------------------------------
;; High-Level Operation Calling

(defn call-operation
  "Call a vips operation with given arguments and options using proper GType introspection.

  Example:
  (call-operation \"resize\" {:in image :scale 2 :vscale 3})
  (call-operation \"invert\" {:in image})
  "
  [operation-name opts]
  (let [op (api/operation-new operation-name)]
    (when (mem/null? op)
      (throw (ex-info "Unknown operation" {:operation operation-name})))

    (try
      ;; First, discover all operation arguments and their types
      (let [args-info (discover-operation-arguments op)
            args-map (into {} (map (juxt :name identity) args-info))]

        ;; Set arguments using proper type introspection
        (doseq [[opt-key opt-value] opts]
          (let [opt-name (name opt-key)
                arg-info (get args-map opt-name)]
            (when-not arg-info
              (throw (ex-info "Unknown argument for operation"
                              {:arg-name opt-name :operation operation-name})))
            (set-argument op arg-info opt-value)))

        (let [built-op (api/cache-operation-build op)]
          (when (mem/null? built-op)
            (throw (ex-info "Operation build failed" {:operation operation-name})))

          (try
            (get-image-result built-op "out")
            (finally
              (api/object-unref-outputs built-op)
              (api/g-object-unref built-op)))))

      (finally
        (when-not (mem/null? op)
          (api/g-object-unref op))))))
