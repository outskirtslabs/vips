(ns ol.vips.impl
  (:require
   [clojure.string :as str])
  (:import
   (app.photofox.vipsffm VBlob VImage VipsError VipsOption)
   (app.photofox.vipsffm.jextract VipsRaw)
   (app.photofox.vipsffm.enums VipsAlign VipsDirection VipsInterpretation)
   (java.lang.foreign MemorySegment ValueLayout)
   (java.io InputStream OutputStream)
   (java.nio.file Path)))

(def ^Class byte-array-class (class (byte-array 0)))

(def ^:private direction->enum
  {:horizontal VipsDirection/DIRECTION_HORIZONTAL
   :vertical   VipsDirection/DIRECTION_VERTICAL})

(def ^:private colourspace->enum
  {:bw   VipsInterpretation/INTERPRETATION_B_W
   :cmyk VipsInterpretation/INTERPRETATION_CMYK
   :rgb  VipsInterpretation/INTERPRETATION_RGB
   :srgb VipsInterpretation/INTERPRETATION_sRGB})

(def ^:private align->enum
  {:low    VipsAlign/ALIGN_LOW
   :centre VipsAlign/ALIGN_CENTRE
   :high   VipsAlign/ALIGN_HIGH})

(def ^:private format->suffix
  {:jpg  ".jpg"
   :jpeg ".jpg"
   :png  ".png"
   :tif  ".tif"
   :tiff ".tiff"
   :webp ".webp"})

(defn- byte-array? [value]
  (instance? byte-array-class value))

(defn- field-name [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    :else (str value)))

(defn- field-keyword [value]
  (keyword value))

(defn- path-string [path]
  (if (instance? Path path)
    (str path)
    path))

(defn- source-description [source]
  (cond
    (string? source) source
    (instance? Path source) (str source)
    (byte-array? source) :bytes
    (instance? InputStream source) :input-stream
    :else (some-> source class .getName)))

(defn- sink-description [sink]
  (cond
    (string? sink) sink
    (instance? Path sink) (str sink)
    (= :bytes sink) :bytes
    (instance? OutputStream sink) :output-stream
    :else (some-> sink class .getName)))

(defn- op-error
  [message data cause]
  (throw (ex-info message
                  (cond-> data
                    cause (assoc :cause-class (.getName (class cause))))
                  cause)))

(defn- ensure-supported!
  [mapping op kind value]
  (or (get mapping value)
      (op-error (format "Unsupported %s for %s: %s" kind (name op) value)
                {:op op kind value}
                nil)))

(defn- ensure-format!
  [op options]
  (let [format-key (:format options)]
    (or (get format->suffix format-key)
        (op-error (format "Missing or unsupported :format for %s" (name op))
                  {:op op :options options}
                  nil))))

(defn- infer-format-from-path [path]
  (some->> (re-find #"\.([^.]+)$" (str/lower-case path))
           second
           keyword
           format->suffix))

(defn blob->bytes [^VBlob blob]
  (when blob
    (let [size (.byteSize blob)
          data (.reinterpret (.getUnsafeDataAddress blob) size)]
      (.toArray data ValueLayout/JAVA_BYTE))))

(defn- generic-option
  [key value]
  (let [key (case key
              :q "Q"
              (name key))]
    (cond
      (boolean? value) (VipsOption/Boolean key value)
      (string? value) (VipsOption/String key value)
      (instance? java.lang.Integer value) (VipsOption/Int key value)
      (instance? java.lang.Long value) (VipsOption/Long key value)
      (integer? value) (if (<= Integer/MIN_VALUE value Integer/MAX_VALUE)
                         (VipsOption/Int key (int value))
                         (VipsOption/Long key (long value)))
      (float? value) (VipsOption/Double key (double value))
      (instance? java.lang.Double value) (VipsOption/Double key value)
      (byte-array? value) (throw (IllegalArgumentException. "byte arrays are not valid operation options"))
      (instance? VImage value) (VipsOption/Image key value)
      (and (sequential? value) (every? integer? value))
      (VipsOption/ArrayInt key (mapv int value))
      (and (sequential? value) (every? number? value))
      (VipsOption/ArrayDouble key (mapv double value))
      :else
      (op-error (format "Unsupported option value for %s" key)
                {:option     (keyword key)
                 :value-type (some-> value class .getName)}
                nil))))

(defn- options->array
  ([options] (options->array options {}))
  ([options {:keys [enums]}]
   (->> options
        (map (fn [[key value]]
               (if-let [enum-fn (get enums key)]
                 (VipsOption/Enum (name key) (enum-fn value))
                 (generic-option key value))))
        (into-array VipsOption))))

(defn- vips-call
  [message data f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo ex
      (throw ex))
    (catch VipsError ex
      (op-error message data ex))
    (catch Throwable ex
      (op-error message data ex))))

(defn open-image
  [arena source options]
  (let [options       (or options {})
        loader-format (some-> options :format format->suffix)
        option-array  (options->array (dissoc options :format))]
    (vips-call "Failed to open image"
               {:op      :open
                :source  (source-description source)
                :options options}
               #(cond
                  (string? source) (VImage/newFromFile arena source option-array)
                  (instance? Path source) (VImage/newFromFile arena (path-string source) option-array)
                  (byte-array? source) (if loader-format
                                         (VImage/newFromBytes arena source loader-format option-array)
                                         (VImage/newFromBytes arena source option-array))
                  (instance? InputStream source) (if loader-format
                                                   (VImage/newFromStream arena source loader-format option-array)
                                                   (VImage/newFromStream arena source option-array))
                  :else
                  (op-error "Unsupported image source"
                            {:op          :open
                             :source-type (some-> source class .getName)}
                            nil)))))

(defn image-info [^VImage image]
  (vips-call "Failed to read image info"
             {:op :image-info}
             #(hash-map
               :width (.getWidth image)
               :height (.getHeight image)
               :has-alpha? (.hasAlpha image))))

(defn thumbnail-image [^VImage image size options]
  (vips-call "Failed to thumbnail image"
             {:op      :thumbnail
              :options options}
             #(.thumbnailImage image (int size) (options->array (or options {})))))

(defn invert-image [^VImage image options]
  (vips-call "Failed to invert image"
             {:op      :invert
              :options options}
             #(.invert image (options->array (or options {})))))

(defn rotate-image [^VImage image angle options]
  (vips-call "Failed to rotate image"
             {:op      :rotate
              :options options}
             #(.rotate image (double angle) (options->array (or options {})))))

(defn colourspace-image [^VImage image space options]
  (let [space-enum (ensure-supported! colourspace->enum :colourspace :space space)]
    (vips-call "Failed to convert colourspace"
               {:op      :colourspace
                :options options}
               #(.colourspace image space-enum (options->array (or options {}))))))

(defn flip-image [^VImage image direction options]
  (let [direction-enum (ensure-supported! direction->enum :flip :direction direction)]
    (vips-call "Failed to flip image"
               {:op      :flip
                :options options}
               #(.flip image direction-enum (options->array (or options {}))))))

(defn join-images [^VImage left ^VImage right direction options]
  (let [direction-enum (ensure-supported! direction->enum :join :direction direction)]
    (vips-call "Failed to join images"
               {:op      :join
                :options options}
               #(.join left right direction-enum (options->array (dissoc (or options {}) :direction))))))

(defn array-join-images [arena images options]
  (vips-call "Failed to array-join images"
             {:op      :array-join
              :options options}
             #(VImage/arrayjoin arena
                                (vec images)
                                (options->array (or options {})
                                                {:enums {:halign (fn [value]
                                                                   (ensure-supported! align->enum :array-join :halign value))
                                                         :valign (fn [value]
                                                                   (ensure-supported! align->enum :array-join :valign value))}}))))

(defn write-image!
  [^VImage image sink options]
  (let [options (or options {})]
    (cond
      (or (string? sink) (instance? Path sink))
      (let [path (path-string sink)]
        (when-not (infer-format-from-path path)
          (op-error "Unsupported or missing file extension for sink"
                    {:op   :write!
                     :sink path}
                    nil))
        (vips-call "Failed to write image to file"
                   {:op      :write!
                    :sink    path
                    :options options}
                   #(.writeToFile image path (options->array (dissoc options :format)))))

      (instance? OutputStream sink)
      (let [format-suffix (ensure-format! :write! options)]
        (vips-call "Failed to write image to stream"
                   {:op      :write!
                    :sink    (sink-description sink)
                    :options options}
                   #(.writeToStream image
                                    sink
                                    format-suffix
                                    (options->array (dissoc options :format)))))

      (= :bytes sink)
      (let [format-key    (:format options)
            write-options (options->array (dissoc options :format))]
        (ensure-format! :write! options)
        (vips-call "Failed to write image to bytes"
                   {:op      :write!
                    :sink    :bytes
                    :options options}
                   #(-> (case format-key
                          :jpg (.jpegsaveBuffer image write-options)
                          :jpeg (.jpegsaveBuffer image write-options)
                          :png (.pngsaveBuffer image write-options)
                          :webp (.webpsaveBuffer image write-options)
                          :tif (.tiffsaveBuffer image write-options)
                          :tiff (.tiffsaveBuffer image write-options)
                          (op-error "Unsupported byte output format"
                                    {:op      :write!
                                     :sink    :bytes
                                     :options options}
                                    nil))
                        blob->bytes)))

      :else
      (op-error "Unsupported image sink"
                {:op        :write!
                 :sink-type (some-> sink class .getName)}
                nil))))

(defn metadata-fields [^VImage image]
  (vips-call "Failed to read metadata fields"
             {:op :metadata-fields}
             #(mapv field-keyword (.getFields image))))

(defn- field-type-name [arena ^VImage image field]
  (let [field-segment (.allocateFrom arena ^String (field-name field))
        type-id       (VipsRaw/vips_image_get_typeof (.getUnsafeStructAddress image) field-segment)
        type-segment  (VipsRaw/g_type_name type-id)]
    (when-not (.equals MemorySegment/NULL type-segment)
      (.getString type-segment 0))))

(defn- decode-metadata-field [arena ^VImage image field]
  (let [field       (field-name field)
        type-name   (field-type-name arena image field)
        unsupported {:ol.vips/type :unsupported-metadata
                     :field        (field-keyword field)}]
    (try
      (cond
        (#{"VipsRefString" "gchararray"} type-name) (.getString image field)
        (#{"gint" "guint" "gboolean" "glong" "gint64" "guint64"} type-name) (.getInt image field)
        (= "gdouble" type-name) (.getDouble image field)
        (= "VipsBlob" type-name) (some-> (.getBlob image field) blob->bytes)
        (= "VipsImage" type-name) (.getImage image field)
        type-name unsupported)
      (catch Throwable _
        unsupported))))

(defn metadata
  ([arena ^VImage image]
   (into {}
         (map (fn [field]
                [field (decode-metadata-field arena image field)]))
         (metadata-fields image)))
  ([arena ^VImage image key-or-keys]
   (if (sequential? key-or-keys)
     (into {}
           (map (fn [field]
                  [field (decode-metadata-field arena image field)]))
           key-or-keys)
     (decode-metadata-field arena image key-or-keys))))

(defn- set-metadata-value!
  [arena ^VImage image key value]
  (let [field (field-name key)]
    (cond
      (string? value) (.set image field ^String value)
      (instance? java.lang.Integer value) (.set image field ^Integer value)
      (instance? java.lang.Long value) (.set image field (int value))
      (integer? value) (.set image field (int value))
      (number? value) (.set image field (double value))
      (byte-array? value) (.set image field (VBlob/newFromBytes arena value))
      (instance? VImage value) (.set image field ^VImage value)
      :else
      (op-error "Unsupported metadata value"
                {:op         :set-metadata
                 :field      (field-keyword field)
                 :value-type (some-> value class .getName)}
                nil))))

(defn set-metadata!
  [arena ^VImage image metadata-map]
  (vips-call "Failed to set metadata"
             {:op :set-metadata}
             #(do
                (doseq [[key value] metadata-map]
                  (set-metadata-value! arena image key value))
                image)))

(defn remove-metadata!
  [^VImage image key-or-keys]
  (let [keys (if (sequential? key-or-keys) key-or-keys [key-or-keys])]
    (vips-call "Failed to remove metadata"
               {:op :remove-metadata}
               #(do
                  (doseq [key keys]
                    (.remove image (field-name key)))
                  image))))
