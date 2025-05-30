(ns clj-vips.invoker
  "Operation invoker for calling libvips operations.
   Based on VipsInvoker.java pattern but using plain Clojure data.")

;; Option constructors - simple maps
(defn string-option
  "Create a string option map."
  ([key] {:type :string :key key :value nil})
  ([key value] {:type :string :key key :value value}))

(defn image-option
  "Create an image option map (typically for outputs)."
  ([key] {:type :image :key key :value nil})
  ([key value] {:type :image :key key :value value}))

(defn int-option
  "Create an integer option map."
  ([key] {:type :int :key key :value nil})
  ([key value] {:type :int :key key :value value}))

;; Option utilities
(defn has-value?
  "Check if option has a value."
  [option]
  (some? (:value option)))

(defn set-value
  "Return new option with value set."
  [option value]
  (assoc option :value value))

;; Simplified operation invoker
(defn invoke-operation
  "Invoke a vips operation with given options.
   
   Args:
     arena - Memory arena for allocations
     operation-name - Name of the vips operation (e.g. 'jpegload')
     string-options - Optional string of operation options
     args - Vector of option maps
     
   This is a simplified implementation that would need expansion
   for full libvips operation support."
  ([arena operation-name args]
   (invoke-operation arena operation-name nil args))
  ([arena operation-name string-options args]
   ;; This is a placeholder implementation
   ;; A full implementation would:
   ;; 1. Create operation with vips_operation_new
   ;; 2. Set string options with object_set_from_string  
   ;; 3. Set input options on the operation
   ;; 4. Build cached operation with vips_cache_operation_build
   ;; 5. Read output options
   ;; 6. Clean up operation references
   (throw (ex-info "Full operation invoker not yet implemented"
                   {:operation operation-name
                    :string-options string-options
                    :args (mapv #(select-keys % [:key :type :value])
                               args)}))))