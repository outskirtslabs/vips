(ns clj-vips.core
  (:require
   [clj-vips.api :as api]
   [clj-vips.invoker :as invoker]
   [coffi.mem :as mem]))

;; Default configuration values from govips
(def default-concurrency-level 1)
(def default-max-cache-mem (* 50 1024 1024)) ; 50MB
(def default-max-cache-size 100)
(def default-max-cache-files 0)

(defn- bool->int [b]
  (if b 1 0))

(defn init
  "Initialize libvips with optional configuration.
   
   Config options:
   - :concurrency-level - Number of native worker threads (default: 1)
   - :max-cache-files - Maximum files to cache (default: 0)
   - :max-cache-mem - Maximum memory for caching in bytes (default: 50MB)
   - :max-cache-size - Maximum operations to cache (default: 100)
   - :report-leaks? - Enable leak checking (default: false)
   - :cache-trace? - Enable cache operation tracing (default: false)"
  ([]
   (init {}))
  ([config]
   ;; Initialize libvips
   (let [result (api/vips-init "clj-vips")]
     #_{:clj-kondo/ignore [:type-mismatch]}
     (when-not (zero? result)
       (throw (ex-info "Failed to initialize libvips"
                       {:error-code    result
                        :error-message (api/vips-error-buffer)}))))

   ;; Apply configuration
   (let [{:keys [concurrency-level max-cache-files max-cache-mem max-cache-size
                 report-leaks? cache-trace?]
          :or   {concurrency-level default-concurrency-level
                 max-cache-files   default-max-cache-files
                 max-cache-mem     default-max-cache-mem
                 max-cache-size    default-max-cache-size
                 report-leaks?     false
                 cache-trace?      false}} config]

     (api/vips-leak-set (bool->int report-leaks?))
     (api/vips-concurrency-set concurrency-level)
     (api/vips-cache-set-max-files max-cache-files)
     (api/vips-cache-set-max-mem max-cache-mem)
     (api/vips-cache-set-max max-cache-size)
     (api/vips-cache-set-trace (bool->int cache-trace?)))))

(defn shutdown
  "Shutdown libvips cleanly."
  []
  (api/vips-shutdown))

(defn clear-cache
  "Drop the whole operation cache."
  []
  (api/vips-cache-drop-all))

(defn shutdown-thread
  "Clear the cache for the current thread.
   Call this when a thread using vips exits."
  []
  (api/vips-thread-shutdown))

;; Image I/O functions

(defn new-image-from-file
  ([file-path]
   (api/image-new-from-file file-path nil)))

(defn write-to-file
  "Write an image to a file.
   
   Args:
     image-ptr - VipsImage pointer
     file-path - Path to write the image to
     
   Returns:
     nil on success, throws exception on error"
  [image-ptr file-path]
  (let [result (api/image-write-to-file image-ptr file-path nil)]
    #_{:clj-kondo/ignore [:type-mismatch]}
    (when-not (zero? result)
      (throw (ex-info "Failed to write image to file"
                      {:file-path     file-path
                       :error-code    result
                       :error-message (api/vips-error-buffer)})))))
