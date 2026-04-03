(ns ol.vips
  (:require
   [ol.vips.impl.api :as api]
   [ol.vips.impl.image :as image]
   [ol.vips.impl.introspect :as introspect]))

(set! *warn-on-reflection* true)

(defn init!
  "Starts libvips and loads the `ol.vips` native bindings for this process.

  This function is idempotent. Most public API calls initialize libvips
  lazily, so call `init!` when you want eager startup or want to inspect the
  shared runtime state up front.

  On first initialization, `ol.vips` restores the secure default and blocks
  libvips operations marked as untrusted. See
  [[set-block-untrusted-operations!]].

  Returns the shared runtime state map, which should be considered an opaque handle and is not public API."
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
  "Returns the current libvips operation cache settings.

  Because libvips operations are free of side effects, libvips can cache a
  previous call to the same operation with the same arguments and return the
  previous result again.

  Returns `{:max <int> :size <int> :max-mem <bytes> :max-files <int>}`."
  []
  (api/operation-cache-settings))

(defn set-operation-cache-max!
  "Sets the maximum number of operations libvips keeps in the operation cache.

  Reducing this limit may trim cached operations immediately.

  Returns the current cache settings map. See [[operation-cache-settings]]."
  [max]
  (api/set-operation-cache-max! max))

(defn set-operation-cache-max-mem!
  "Sets the maximum amount of tracked memory, in bytes, libvips allows before
  it starts dropping cached operations.

  libvips only tracks memory it allocates itself. Memory allocated by external
  libraries is not included.

  Returns the current cache settings map. See [[operation-cache-settings]]."
  [max-mem]
  (api/set-operation-cache-max-mem! max-mem))

(defn set-operation-cache-max-files!
  "Sets the maximum number of tracked files libvips allows before it starts
  dropping cached operations.

  libvips only tracks file descriptors it opens itself. Descriptors opened by
  external libraries are not included.

  Returns the current cache settings map. See [[operation-cache-settings]]."
  [max-files]
  (api/set-operation-cache-max-files! max-files))

(defn disable-operation-cache!
  "Disables the libvips operation cache by setting the maximum cached
  operation count to `0`.

  This is often useful for image-proxy style workloads that process many
  different images.

  Returns the current cache settings map. See [[set-operation-cache-max!]]."
  []
  (api/disable-operation-cache!))

(defn tracked-resources
  "Returns the current libvips tracked resource counters.

  libvips uses these counters to decide when to start dropping cached
  operations. The returned map has these keys:

  | key              | description                                              |
  |------------------|----------------------------------------------------------|
  | `:mem`           | Bytes currently allocated via libvips tracked allocators |
  | `:mem-highwater` | Largest tracked allocation total seen so far             |
  | `:allocs`        | Number of active tracked allocations                     |
  | `:files`         | Number of tracked open files                             |

  These counters only include resources libvips tracks itself."
  []
  (api/tracked-resources))

(defn operations
  "Returns the sorted libvips operation nicknames known to the runtime.

  These are the names accepted by [[call]] and by [[operation-info]], such as
  `\"flip\"`, `\"rotate\"`, or `\"thumbnail_image\"`."
  []
  (introspect/list-operations))

(defn operation-info
  "Describes a libvips operation by nickname.

  Returns a map with the operation `:name`, a short `:description`, and an
  `:args` vector describing each argument's name, blurb, type, and whether it
  is an input, output, or required argument."
  [operation-name]
  (introspect/describe-operation operation-name))

(defn encode-enum
  "Encodes a Clojure enum value for a libvips enum type.

  `enum-type-name` should be a libvips GType name such as `\"VipsDirection\"`.
  Pass a keyword like `:horizontal` to get the corresponding integer value.
  Integer values pass through unchanged."
  [enum-type-name value]
  (introspect/encode-enum enum-type-name value))

(defn decode-enum
  "Decodes a libvips enum integer to a normalized Clojure keyword.

  `enum-type-name` should be a libvips GType name such as `\"VipsDirection\"`.

  ```clojure
  (v/decode-enum \"VipsDirection\"
                 (v/encode-enum \"VipsDirection\" :horizontal))
  ;;=> :horizontal
  ```"
  [enum-type-name value]
  (introspect/decode-enum enum-type-name value))

(defn call
  "Calls a libvips operation by nickname.

  This is the low-level generic binding API described by libvips for language
  bindings: create an operation from its nickname, set properties, execute with
  the operation cache, then extract outputs.

  `operation-name` should be a libvips operation nickname such as `\"flip\"` or
  `\"embed\"`. `opts` keys should match libvips argument names as keywords.
  Enum arguments accept the normalized keywords used by [[encode-enum]]. Image
  arguments accept image handles and prior operation result maps with `:out`.

  Returns the operation outputs in the most useful shape for Clojure:

  - If the only output is an image at `:out`, returns that image handle.
  - Otherwise returns a map of outputs.
  - Returned output maps that hold closeable image values are themselves
    closeable.

  Example:

  ```clojure
  (v/call \"flip\" {:in image
                    :direction :horizontal})
  ```"
  [operation-name opts]
  (introspect/call-operation operation-name opts))

(defn from-file
  "Opens an image from `path` and returns a closeable image handle.

  `path` can be a string, `java.nio.file.Path`, or anything else coercible to a
  path string. Arity 2 appends `opts` in libvips option-string form. The
  available option keys depend on the loader libvips selects for that path.

  To discover loader-specific options like `:shrink`, see the generated
  wrappers in [[ol.vips.operations]], for example
  [[ol.vips.operations/jpegload]] or [[ol.vips.operations/pngload]]. For enum
  option values like `:sequential` on `:access`, see [[ol.vips.enums/access]]
  or [[ol.vips.enums/describe]].

  ```clojure
  (v/from-file \"input.jpg\" {:access :sequential
                              :shrink 2})
  ```"
  ([path]
   (api/open-image path))
  ([path opts]
   (api/open-image (api/append-options path opts))))

(defn write-to-file
  "Writes `image` to `sink` and returns `image`.

  libvips infers the saver from the sink path or extension. Arity 3 appends
  `opts` in libvips option-string form. The available option keys depend on
  the saver libvips selects for that sink.

  To discover saver-specific options, see the generated wrappers in
  [[ol.vips.operations]], for example [[ol.vips.operations/pngsave]] or
  [[ol.vips.operations/jpegsave]]. For enum option values, see
  [[ol.vips.enums]].

  ```clojure
  (v/write-to-file image \"output.png\" {:compression 9})
  ```"
  ([image sink]
   (api/write-image! image sink))
  ([image sink opts]
   (api/write-image! image (api/append-options sink opts))))

(defn from-buffer
  "Opens an image from in-memory bytes and returns a closeable image handle.

  `source` may be a byte array or a byte sequence. Arity 2 passes `opts` as a
  libvips option string for the loader.

  The available option keys depend on the loader that recognizes the input
  bytes. See [[ol.vips.operations]] for the generated loader wrappers and
  [[ol.vips.enums]] for enum value sets."
  ([source]
   (api/open-image-from-buffer source))
  ([source opts]
   (api/open-image-from-buffer source (api/append-options "" opts))))

(defn from-stream
  "Opens an image from an `InputStream` and returns a closeable image handle.

  The stream is bridged to a libvips source and is closed when the returned
  image handle is closed. Arity 2 passes `opts` as a libvips option string for
  the loader.

  This is useful for streaming or non-file inputs, especially together with
  loader hints like `:access :sequential`.

  The available option keys depend on the loader that recognizes the stream.
  See [[ol.vips.operations]] for the generated loader wrappers and
  [[ol.vips.enums/access]] for valid `:access` values."
  ([is]
   (api/open-image-from-stream is))
  ([is opts]
   (api/open-image-from-stream is (api/append-options "" opts))))

(defn write-to-buffer
  "Encodes `image` and returns the result as a byte array.

  `suffix` selects the saver and can include libvips save options directly,
  such as `\".png\"` or `\".png[compression=9]\"`. Arity 3 appends `opts` to
  `suffix` in libvips option-string form.

  To discover saver-specific options, see the generated wrappers in
  [[ol.vips.operations]], for example [[ol.vips.operations/pngsave]] or
  [[ol.vips.operations/jpegsave]]."
  ([image suffix]
   (api/write-image-to-buffer image suffix))
  ([image suffix opts]
   (api/write-image-to-buffer image (api/append-options suffix opts))))

(defn write-to-stream
  "Encodes `image` to an `OutputStream` and returns `image`.

  `suffix` selects the saver, for example `\".png\"` or `\".jpg\"`. Arity 4
  appends `opts` to `suffix` in libvips option-string form.

  The stream is flushed and closed after the write completes, and is also
  closed if the write fails.

  To discover saver-specific options, see the generated wrappers in
  [[ol.vips.operations]], for example [[ol.vips.operations/pngsave]] or
  [[ol.vips.operations/jpegsave]]."
  ([image os suffix]
   (api/write-image-to-stream image os suffix))
  ([image os suffix opts]
   (api/write-image-to-stream image os (api/append-options suffix opts))))

(defn metadata
  "Returns a curated metadata map for `image`.

  This is the high-level metadata view for the public API. It includes the core
  image header fields such as width, height, bands, format, coding,
  interpretation, resolution, offsets, and `:has-alpha?`, plus common animated
  image fields when present such as `:pages`, `:page-height`, `:loop`, and
  `:delay`.

  For raw header and metadata access by libvips field name, see [[field]],
  [[field-names]], and [[headers]]."
  [image]
  (image/metadata image))

(defn field
  "Returns the libvips header or metadata field named by `field-name`.

  Items of metadata are identified by strings. Use this for direct access to
  libvips header fields and attached metadata such as `\"xres\"`,
  `\"icc-profile-data\"`, or `\"delay\"`.

  Arity 2 returns `nil` for missing fields. Arity 3 returns `not-found`
  instead."
  ([image field-name]
   (api/image-field image field-name))
  ([image field-name not-found]
   (api/image-field image field-name not-found)))

(defn field-as-string
  "Returns the libvips field named by `field-name` rendered as a string.

  This is the direct string form of a header or metadata field, useful for
  display and debugging. Arity 3 returns `not-found` when the field is
  absent."
  ([image field-name]
   (api/image-field-as-string image field-name))
  ([image field-name not-found]
   (api/image-field-as-string image field-name not-found)))

(defn field-names
  "Returns the libvips field names attached to `image`.

  This includes both core header fields and attached metadata field names."
  [image]
  (api/image-field-names image))

(defn headers
  "Returns the raw libvips header and metadata map for `image`.

  Keys are the original libvips field names as strings. Prefer [[metadata]]
  when you want the higher-level normalized public view."
  [image]
  (api/image-metadata image))

(defn has-field?
  "Returns `true` if `image` has the libvips field named by `field-name`."
  [image field-name]
  (api/image-has-field? image field-name))

(defn width
  "Returns the image width in pixels."
  [image]
  (api/image-width image))

(defn height
  "Returns the image height in pixels."
  [image]
  (api/image-height image))

(defn bands
  "Returns the number of image bands as an integer.

  libvips images have three dimensions: width, height, and bands."
  [image]
  (api/image-bands image))

(defn has-alpha?
  "Returns `true` if `image` has an alpha band."
  [image]
  (api/image-has-alpha? image))

(defn shape
  "Returns `[width height bands]` for `image`."
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
