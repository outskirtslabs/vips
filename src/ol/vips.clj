(ns ol.vips
  "Thin, scoped Clojure wrapper over libvips via `vips-ffm`.

  Example:

  ```clojure
  (require '[ol.vips :as v])

  (v/with-image [img \"dev/rabbit.jpg\"]
    (-> img
        (v/thumbnail 400 {:auto-rotate true})
        (v/write! \"/tmp/rabbit-thumb.jpg\")))
  ```"
  (:require
   [ol.vips.impl :as impl])
  (:import
   (app.photofox.vipsffm VImage Vips VipsRunnable)
   (java.util.concurrent.atomic AtomicLong)))

(defrecord Session [id arena])
(defrecord Image [session-id arena vimage])

(defonce ^:private session-counter (AtomicLong. 0))

(defn- next-session-id []
  (.incrementAndGet session-counter))

(defn make-session [arena]
  (->Session (next-session-id) arena))

(defn- image-handle [^Session session ^VImage vimage]
  (->Image (:id session) (:arena session) vimage))

(defn- ensure-session! [session]
  (when-not (instance? Session session)
    (throw (ex-info "Expected an ol.vips session"
                    {:op         :session
                     :value-type (some-> session class .getName)})))
  session)

(defn- ensure-image! [image op]
  (when-not (instance? Image image)
    (throw (ex-info "Expected an ol.vips image handle"
                    {:op         op
                     :value-type (some-> image class .getName)})))
  image)

(defn- unwrap-image [image op]
  (:vimage (ensure-image! image op)))

(defn- ensure-same-session!
  [op image other]
  (when-not (= (:session-id (ensure-image! image op))
               (:session-id (ensure-image! other op)))
    (throw (ex-info "Image handles belong to different sessions"
                    {:op               op
                     :left-session-id  (:session-id image)
                     :right-session-id (:session-id other)}))))

(defn- ensure-same-session-images!
  [op images]
  (when (empty? images)
    (throw (ex-info "Expected at least one image handle"
                    {:op op})))
  (doseq [image images]
    (ensure-image! image op))
  (doseq [other (rest images)]
    (ensure-same-session! op (first images) other))
  images)

(defn- wrap-metadata-value [image value]
  (if (instance? VImage value)
    (->Image (:session-id image) (:arena image) value)
    value))

(defn- unwrap-metadata-value [session-id value]
  (if (instance? Image value)
    (do
      (when-not (= session-id (:session-id value))
        (throw (ex-info "Image metadata values must belong to the same session"
                        {:op                  :set-metadata
                         :image-session-id    session-id
                         :metadata-session-id (:session-id value)})))
      (:vimage value))
    value))

(defmacro with-session
  "Execute `body` inside a scoped libvips session.

  The bound symbol receives an opaque session handle used by `open`."
  [[binding] & body]
  `(let [result# (atom nil)
         error#  (atom nil)]
     (Vips/run
      (reify VipsRunnable
        (run [_ arena#]
          (try
            (let [~binding (make-session arena#)]
              (reset! result# (do ~@body)))
            (catch Throwable t#
              (reset! error# t#))))))
     (when-let [error-value# @error#]
       (throw error-value#))
     @result#))

(defmacro with-image
  "Open `source` inside a short-lived session and bind an image handle for `body`.

  This is a convenience wrapper over [[with-session]] plus [[open]]."
  [[binding source] & body]
  `(with-session [session#]
     (let [~binding (open session# ~source)]
       ~@body)))

(defn open
  "Open `source` inside `session` and return an opaque image handle.

  Supported sources are path strings, `java.nio.file.Path`, `byte[]`,
  `java.io.InputStream`, and existing image handles from the same session.

  Options:

  | key       | description
  |-----------|-------------
  | `:format` | Optional loader hint for `byte[]` and `InputStream` sources, for example `:jpg` or `:png`."
  ([session source]
   (open session source nil))
  ([session source options]
   (let [session (ensure-session! session)]
     (if (instance? Image source)
       (do
         (when-not (= (:id session) (:session-id source))
           (throw (ex-info "Image handles belong to a different session"
                           {:op               :open
                            :session-id       (:id session)
                            :image-session-id (:session-id source)})))
         source)
       (image-handle session (impl/open-image (:arena session) source options))))))

(defn write!
  "Write `image` to `sink`.

  Returns the original image handle for file and stream sinks, and a `byte[]`
  when `sink` is `:bytes`.

  Options:

  | key       | description
  |-----------|-------------
  | `:format` | Required for `java.io.OutputStream` and `:bytes` sinks, for example `:jpg`, `:png`, `:webp`, or `:tiff`
  | `:q`      | JPEG quality passthrough
  | `:strip`  | Strip metadata when supported by the target saver."
  ([image sink]
   (write! image sink nil))
  ([image sink options]
   (let [image  (ensure-image! image :write!)
         output (impl/write-image! (:vimage image) sink options)]
     (if (= :bytes sink)
       output
       image))))

(defn image-info
  "Return basic image metadata as a plain Clojure map."
  [image]
  (impl/image-info (unwrap-image image :image-info)))

(defn thumbnail
  "Create a thumbnail of `image` constrained to `size` pixels on the longest edge.

  Options:

  | key            | description
  |----------------|-------------
  | `:auto-rotate` | Apply EXIF orientation before resizing."
  ([image size]
   (thumbnail image size nil))
  ([image size options]
   (->Image (:session-id (ensure-image! image :thumbnail))
            (:arena image)
            (impl/thumbnail-image (unwrap-image image :thumbnail) size options))))

(defn invert
  "Invert the pixels of `image`."
  ([image]
   (invert image nil))
  ([image options]
   (->Image (:session-id (ensure-image! image :invert))
            (:arena image)
            (impl/invert-image (unwrap-image image :invert) options))))

(defn rotate
  "Rotate `image` by `angle` degrees."
  ([image angle]
   (rotate image angle nil))
  ([image angle options]
   (->Image (:session-id (ensure-image! image :rotate))
            (:arena image)
            (impl/rotate-image (unwrap-image image :rotate) angle options))))

(defn colourspace
  "Convert `image` to `space`, using friendly keywords such as `:srgb`, `:rgb`, `:bw`, and `:cmyk`."
  ([image space]
   (colourspace image space nil))
  ([image space options]
   (->Image (:session-id (ensure-image! image :colourspace))
            (:arena image)
            (impl/colourspace-image (unwrap-image image :colourspace) space options))))

(defn flip
  "Flip `image` in `direction`, either `:horizontal` or `:vertical`."
  ([image direction]
   (flip image direction nil))
  ([image direction options]
   (->Image (:session-id (ensure-image! image :flip))
            (:arena image)
            (impl/flip-image (unwrap-image image :flip) direction options))))

(defn join
  "Join two image handles from the same session.

  Defaults to horizontal composition.

  Options:

  | key          | description
  |--------------|-------------
  | `:direction` | Composition direction, either `:horizontal` or `:vertical`."
  ([image other]
   (join image other nil))
  ([image other options]
   (ensure-same-session! :join image other)
   (->Image (:session-id image)
            (:arena image)
            (impl/join-images (unwrap-image image :join)
                              (unwrap-image other :join)
                              (or (:direction options) :horizontal)
                              options))))

(defn array-join
  "Join a collection of image handles from the same session into a grid.

  Options:

  | key       | description
  |-----------|-------------
  | `:across` | Number of images per row
  | `:shim`   | Pixel spacing between cells
  | `:halign` | Horizontal alignment for uneven cells, one of `:low`, `:centre`, `:high`
  | `:valign` | Vertical alignment for uneven cells, one of `:low`, `:centre`, `:high`."
  ([images]
   (array-join images nil))
  ([images options]
   (let [images     (vec (ensure-same-session-images! :array-join images))
         session-id (:session-id (first images))]
     (->Image session-id
              (:arena (first images))
              (impl/array-join-images (:arena (first images))
                                      (mapv #(unwrap-image % :array-join) images)
                                      options)))))

(defn metadata-fields
  "Return the metadata field names on `image` as keywords."
  [image]
  (impl/metadata-fields (unwrap-image image :metadata-fields)))

(defn metadata
  "Return decoded metadata for `image`.

  With no additional argument, returns all metadata as a map. With a key,
  returns the single decoded value. With a collection of keys, returns a map
  containing those fields.

  Unsupported fields are returned as placeholder maps instead of failing the
  entire metadata read."
  ([image]
   (into {}
         (map (fn [[key value]]
                [key (wrap-metadata-value image value)]))
         (impl/metadata (:arena (ensure-image! image :metadata))
                        (unwrap-image image :metadata))))
  ([image key-or-keys]
   (let [image (ensure-image! image :metadata)
         value (impl/metadata (:arena image)
                              (unwrap-image image :metadata)
                              key-or-keys)]
     (if (map? value)
       (into {}
             (map (fn [[key metadata-value]]
                    [key (wrap-metadata-value image metadata-value)]))
             value)
       (wrap-metadata-value image value)))))

(defn set-metadata
  "Set metadata fields on `image` from `metadata-map` and return the image handle."
  [image metadata-map]
  (let [image (ensure-image! image :set-metadata)]
    (impl/set-metadata! (:arena image)
                        (unwrap-image image :set-metadata)
                        (into {}
                              (map (fn [[key value]]
                                     [key (unwrap-metadata-value (:session-id image) value)]))
                              metadata-map))
    image))

(defn remove-metadata
  "Remove one or more metadata fields from `image` and return the image handle."
  [image key-or-keys]
  (let [image (ensure-image! image :remove-metadata)]
    (impl/remove-metadata! (unwrap-image image :remove-metadata) key-or-keys)
    image))
