(ns ^:no-doc ol.vips.impl.io
  (:require [babashka.ffi :as ffi]
            [ol.vips.impl.api :as runtime]
            [ol.vips.impl.handles :as handles]
            [ol.vips.impl.introspect :as introspect])
  (:import [java.io InputStream OutputStream]))

(defn split-options
  [value]
  (mapv (fn [binding]
          (let [ptr ((runtime/bindings binding) value)]
            (try
              (ffi/ptr->string ptr)
              (finally ((runtime/bindings :g-free) ptr)))))
        [:filename-get-filename :filename-get-options]))

(defn discover
  [binding & args]
  (or (apply (runtime/bindings binding) args)
      (runtime/throw-vips-error
       (runtime/bindings)
       (case binding
         :foreign-find-load "Failed to open image"
         :foreign-find-load-buffer "Failed to open image from buffer"
         "No suitable image codec")
       {:discovery binding})))

(defn speculative-discover
  [binding & args]
  ((runtime/bindings :vips-error-freeze))
  (try (apply (runtime/bindings binding) args)
       (finally ((runtime/bindings :vips-error-thaw)))))

(defn execute
  [operation inputs option-string opts]
  (runtime/validate-helper-option-values! operation opts)
  (introspect/call-operation operation inputs option-string))

(defn execute-load
  [operation inputs option-string opts]
  ;; Loaders can also return flags; I/O helpers transfer only their image output.
  (runtime/image-handle (execute operation inputs option-string opts)))

(defn open-image
  ([source] (open-image source nil))
  ([source opts]
   (let [[filename options] (split-options (runtime/append-options source opts))
         operation          (discover :foreign-find-load filename)]
     (execute-load operation {:filename filename} options opts))))

(defn open-blob
  [blob options opts]
  (with-open [arena (ffi/confined-arena)]
    (let [size-ptr (ffi/alloc arena :size_t)
          buffer   ((runtime/bindings :blob-get) (:ptr blob) size-ptr)
          size     (ffi/read size-ptr :size_t)]
      (execute-load (discover :foreign-find-load-buffer buffer size)
                    {:buffer blob} options opts))))

(defn open-buffer
  [source options opts]
  (introspect/with-copied-blob (runtime/->byte-array source "from-buffer source")
    (fn [blob] (open-blob blob options opts))))

(defn open-image-from-buffer
  ([source] (open-image-from-buffer source nil))
  ([source opts]
   (open-buffer source (runtime/append-options "" opts) opts)))

(defn connection-handle
  [ptr]
  (when (ffi/null? ptr)
    (runtime/throw-vips-error (runtime/bindings) "Failed to create image target" {}))
  (runtime/native-handle ptr))

(defn write-image!
  ([image path] (write-image! image path nil))
  ([image path opts]
   (runtime/with-image-pointer image (constantly nil))
   (let [[filename options] (split-options (runtime/append-options path opts))]
     (if-let [operation (speculative-discover :foreign-find-save-target filename)]
       (with-open [^java.lang.AutoCloseable target (connection-handle ((runtime/bindings :target-new-to-file) filename))]
         (execute operation {:in image :target target} options opts))
       (execute (discover :foreign-find-save filename) {:in image :filename filename} options opts)))
   image))

(defn write-image-to-buffer
  ([image suffix] (write-image-to-buffer image suffix nil))
  ([image suffix opts]
   (runtime/with-image-pointer image (constantly nil))
   (let [[filename options] (split-options (runtime/append-options suffix opts))]
     (if-let [operation (speculative-discover :foreign-find-save-target filename)]
       (with-open [^java.lang.AutoCloseable target (connection-handle ((runtime/bindings :target-new-to-memory)))]
         (execute operation {:in image :target target} options opts)
         (handles/with-pointer target
           (fn [ptr]
             (runtime/with-gvalue ((runtime/bindings :blob-get-type))
               (fn [value]
                 ((runtime/bindings :g-object-get-property) ptr "blob" value)
                 (introspect/blob-bytes ((runtime/bindings :g-value-get-boxed) value)))))))
       (:buffer (execute (discover :foreign-find-save-buffer filename) {:in image} options opts))))))

(defn open-source
  [source options opts]
  (handles/with-pointer source
    (fn [ptr]
      (if-let [operation (speculative-discover :foreign-find-load-source ptr)]
        (execute-load operation {:source source} options opts)
        (if-let [filename ((runtime/bindings :connection-filename) ptr)]
          (execute-load (discover :foreign-find-load filename) {:filename filename} options opts)
          (if (pos? ((runtime/bindings :source-is-mappable) ptr))
            (let [blob ((runtime/bindings :source-map-blob) ptr)]
              (when (ffi/null? blob)
                (runtime/throw-vips-error (runtime/bindings) "Failed to map image source" {}))
              (try
                (open-buffer (introspect/blob-bytes blob) options opts)
                (finally ((runtime/bindings :area-unref) blob))))
            (runtime/throw-vips-error (runtime/bindings) "Unable to load image source" {})))))))

(defn open-image-from-stream
  ([source] (open-image-from-stream source nil))
  ([source opts]
   (let [stream (runtime/require-instance InputStream source "from-stream source")]
     (with-open [^java.lang.AutoCloseable bridge (runtime/new-source-bridge stream)]
       (try
         (open-source bridge (runtime/append-options "" opts) opts)
         (catch Throwable t
           (runtime/throw-with-stream-failure t (:failure-refs @bridge) "Failed to open image from stream")))))))

(defn write-image-to-stream
  ([image sink suffix] (write-image-to-stream image sink suffix nil))
  ([image sink suffix opts]
   (let [stream             (runtime/require-instance OutputStream sink "write-to-stream sink")
         [filename options] (split-options (runtime/append-options suffix opts))]
     (with-open [^java.lang.AutoCloseable bridge (runtime/new-target-bridge stream)]
       (try
         (execute (discover :foreign-find-save-target filename)
                  {:in image :target bridge} options opts)
         image
         (catch Throwable t
           (runtime/throw-with-stream-failure t (:failure-refs @bridge) "Failed to write image to stream")))))))
