(ns ol.vips
  (:require
   [ol.vips.impl.api :as api]
   [ol.vips.impl.image :as image]
   [ol.vips.impl.introspect :as introspect]))

(set! *warn-on-reflection* true)

(defn init!
  []
  (api/ensure-initialized!))

(defn set-block-untrusted-operations!
  "Set whether libvips operations tagged as untrusted are blocked at runtime.

  This is the direct wrapper around libvips `vips_block_untrusted_set`.
  Pass `blocked?` as `true` to restore the secure default and block operations
  libvips has marked as untrusted. Pass `false` to allow them to run.

  Returns the current runtime state map."
  [blocked?]
  (api/set-block-untrusted-operations! blocked?))

(defn set-operation-block!
  "Set the block state for a libvips operation class hierarchy.

  `name` should be a libvips operation class name such as `\"VipsForeignLoad\"`
  or `\"VipsForeignLoadJpeg\"`. libvips applies the block state at that point in
  the class hierarchy and to all descendants. This is the direct wrapper around
  libvips `vips_operation_block_set`.

  Pass `blocked?` as `true` to block that class hierarchy, or `false` to allow
  it.

  Example:

  ```clojure
  (v/set-operation-block! \"VipsForeignLoad\" true)
  (v/set-operation-block! \"VipsForeignLoadJpeg\" false)
  ```

  That blocks all loaders except the JPEG loader family.

  Returns `{:name <string> :blocked? <boolean>}`."
  [name blocked?]
  (api/set-operation-block! name blocked?))

(defn operation-cache-settings
  []
  (api/operation-cache-settings))

(defn set-operation-cache-max!
  [max]
  (api/set-operation-cache-max! max))

(defn set-operation-cache-max-mem!
  [max-mem]
  (api/set-operation-cache-max-mem! max-mem))

(defn set-operation-cache-max-files!
  [max-files]
  (api/set-operation-cache-max-files! max-files))

(defn disable-operation-cache!
  []
  (api/disable-operation-cache!))

(defn tracked-resources
  []
  (api/tracked-resources))

(defn operations
  []
  (introspect/list-operations))

(defn operation-info
  [operation-name]
  (introspect/describe-operation operation-name))

(defn encode-enum
  [enum-type-name value]
  (introspect/encode-enum enum-type-name value))

(defn decode-enum
  [enum-type-name value]
  (introspect/decode-enum enum-type-name value))

(defn call
  [operation-name opts]
  (introspect/call-operation operation-name opts))

(defn from-file
  ([path]
   (api/open-image path))
  ([path opts]
   (api/open-image (api/append-options path opts))))

(defn image-from-file
  [path]
  (from-file path))

(defn write-to-file
  ([image sink]
   (api/write-image! image sink))
  ([image sink opts]
   (api/write-image! image (api/append-options sink opts))))

(defn from-buffer
  ([source]
   (api/open-image-from-buffer source))
  ([source opts]
   (api/open-image-from-buffer source (api/append-options "" opts))))

(defn from-stream
  ([source]
   (api/open-image-from-stream source))
  ([source opts]
   (api/open-image-from-stream source (api/append-options "" opts))))

(defn write-to-buffer
  ([image suffix]
   (api/write-image-to-buffer image suffix))
  ([image suffix opts]
   (api/write-image-to-buffer image (api/append-options suffix opts))))

(defn write-to-stream
  ([image sink suffix]
   (api/write-image-to-stream image sink suffix))
  ([image sink suffix opts]
   (api/write-image-to-stream image sink (api/append-options suffix opts))))

(defn metadata
  [image]
  (image/metadata image))

(defn field
  ([image field-name]
   (api/image-field image field-name))
  ([image field-name not-found]
   (api/image-field image field-name not-found)))

(defn field-as-string
  ([image field-name]
   (api/image-field-as-string image field-name))
  ([image field-name not-found]
   (api/image-field-as-string image field-name not-found)))

(defn field-names
  [image]
  (api/image-field-names image))

(defn headers
  [image]
  (api/image-metadata image))

(defn has-field?
  [image field-name]
  (api/image-has-field? image field-name))

(defn width
  [image]
  (api/image-width image))

(defn height
  [image]
  (api/image-height image))

(defn bands
  [image]
  (api/image-bands image))

(defn has-alpha?
  [image]
  (api/image-has-alpha? image))

(defn shape
  [image]
  [(width image)
   (height image)
   (bands image)])

(defn thumbnail
  ([image width]
   (thumbnail image width {}))
  ([image width opts]
   (call "thumbnail_image" (merge {:in    image
                                   :width width}
                                  opts))))

(defn copy-memory
  "Materializes `image` into a private in-memory `VipsImage` and returns it as a
  normal closeable image handle.

  This is effectively a sink. Calling `copy-memory` forces libvips to evaluate
  the current pipeline now, render the pixels into memory, and wrap that memory
  in a new image handle. Unlike [[write-to-file]], [[write-to-buffer]], or
  [[write-to-stream]], the result is still an image you can keep using in
  downstream image operations.

  This is useful when an intermediate image will be reused several times and you
  do not want libvips to recompute the upstream pipeline for each branch. It is
  also the explicit way to ask for a private memory-backed image before applying
  mutating draw operations.

  Behavior:

  - Preserves the rendered image pixels and image metadata.
  - Returns a new handle that remains usable after the source pipeline handles
    have been closed.
  - May avoid an extra copy if libvips determines the input is already a simple
    readable memory image, in which case it can return another reference to the
    existing image instead of allocating again.
  - Trades CPU savings for higher memory use, so it is best reserved for
    intermediates you know are reused often enough to justify retaining the
    pixels.

  Example:

  ```clojure
  (with-open [base   (v/from-file \"input.jpg\")
              step   (-> base
                         (ops/resize 0.5)
                         (ops/sharpen))
              cached (v/copy-memory step)]
    (do-something cached)
    (do-something-else cached))
  ```"
  [image]
  (api/copy-image-to-memory image))

(defn assoc-field
  ([image field-name value]
   (image/assoc-field image field-name value))
  ([image field-name value opts]
   (image/assoc-field image field-name value opts)))

(defn update-field
  [image field-name f & args]
  (apply image/update-field image field-name f args))

(defn dissoc-field
  [image field-name]
  (image/dissoc-field image field-name))

(defn pages
  [image]
  (image/pages image))

(defn page-height
  [image]
  (image/page-height image))

(defn page-delays
  [image]
  (image/page-delays image))

(defn loop-count
  [image]
  (image/loop-count image))

(defn assoc-pages
  [image page-count]
  (image/assoc-pages image page-count))

(defn assoc-page-height
  [image frame-height]
  (image/assoc-page-height image frame-height))

(defn assoc-page-delays
  [image delays]
  (image/assoc-page-delays image delays))

(defn assoc-loop-count
  [image loop-value]
  (image/assoc-loop-count image loop-value))

(defn extract-area-pages
  [image left top width height]
  (image/extract-area-pages image left top width height))

(defn embed-pages
  ([image x y width height]
   (image/embed-pages image x y width height))
  ([image x y width height opts]
   (image/embed-pages image x y width height opts)))

(defn rot-pages
  [image angle]
  (image/rot-pages image angle))

(defn assemble-pages
  ([frames]
   (image/assemble-pages frames))
  ([frames {:keys [loop delay] :as _opts}]
   (image/assemble-pages frames {:loop loop :delay delay})))
