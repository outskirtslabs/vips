(ns ol.vips
  (:require
   [clojure.string :as str]
   [ol.vips.introspect :as introspect]
   [ol.vips.runtime :as runtime]))

(set! *warn-on-reflection* true)

(defn- render-option-value
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (boolean? value) (if value "true" "false")
    (sequential? value) (str/join " " (map render-option-value value))
    :else (str value)))

(defn- render-option-string
  [opts]
  (when (seq opts)
    (str "["
         (->> opts
              (sort-by (comp str key))
              (map (fn [[k v]]
                     (str (name k) "=" (render-option-value v))))
              (str/join ","))
         "]")))

(defn- append-options
  [value opts]
  (let [value         (str value)
        option-string (render-option-string opts)]
    (if-not option-string
      value
      (if (and (str/includes? value "[")
               (str/ends-with? value "]"))
        (str (subs value 0 (dec (count value)))
             ","
             (subs option-string 1))
        (str value option-string)))))

(defn init!
  []
  (runtime/ensure-initialized!))

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

(defn call!
  [operation-name opts]
  (introspect/call-operation operation-name opts))

(defn from-file
  ([path]
   (runtime/open-image path))
  ([path opts]
   (runtime/open-image (append-options path opts))))

(defn image-from-file
  [path]
  (from-file path))

(defn write-to-file
  ([image sink]
   (runtime/write-image! image sink))
  ([image sink opts]
   (runtime/write-image! image (append-options sink opts))))

(defn from-buffer
  ([source]
   (runtime/open-image-from-buffer source))
  ([source opts]
   (runtime/open-image-from-buffer source (render-option-string opts))))

(defn from-stream
  ([source]
   (runtime/open-image-from-stream source))
  ([source opts]
   (runtime/open-image-from-stream source (render-option-string opts))))

(defn write-to-buffer
  ([image suffix]
   (runtime/write-image-to-buffer image suffix))
  ([image suffix opts]
   (runtime/write-image-to-buffer image (append-options suffix opts))))

(defn write-to-stream
  ([image sink suffix]
   (runtime/write-image-to-stream image sink suffix))
  ([image sink suffix opts]
   (runtime/write-image-to-stream image sink (append-options suffix opts))))

(defn metadata
  [image]
  (let [base (runtime/image-info image)]
    (cond-> base
      (some? (runtime/image-field image "n-pages"))
      (assoc :pages (runtime/image-field image "n-pages"))

      (some? (runtime/image-field image "page-height"))
      (assoc :page-height (runtime/image-field image "page-height"))

      (some? (runtime/image-field image "loop"))
      (assoc :loop (runtime/image-field image "loop"))

      (some? (runtime/image-field image "delay"))
      (assoc :delay (runtime/image-field image "delay")))))

(defn field
  ([image field-name]
   (runtime/image-field image field-name))
  ([image field-name not-found]
   (runtime/image-field image field-name not-found)))

(defn field-as-string
  ([image field-name]
   (runtime/image-field-as-string image field-name))
  ([image field-name not-found]
   (runtime/image-field-as-string image field-name not-found)))

(defn field-names
  [image]
  (runtime/image-field-names image))

(defn headers
  [image]
  (runtime/image-metadata image))

(defn has-field?
  [image field-name]
  (runtime/image-has-field? image field-name))

(defn width
  [image]
  (runtime/image-width image))

(defn height
  [image]
  (runtime/image-height image))

(defn bands
  [image]
  (runtime/image-bands image))

(defn has-alpha?
  [image]
  (runtime/image-has-alpha? image))

(defn shape
  [image]
  [(width image)
   (height image)
   (bands image)])

(defn thumbnail
  ([image width]
   (thumbnail image width {}))
  ([image width opts]
   (call! "thumbnail_image" (merge {:in    image
                                    :width width}
                                   opts))))

(defn- close-quietly
  [value]
  (when value
    (try
      (.close ^java.lang.AutoCloseable value)
      (catch Throwable _
        nil))))

(defn- copy-image
  [image]
  (call! "copy" {:in image}))

(def ^:private copy-header-fields
  #{"width" "height" "bands" "format" "coding" "interpretation"
    "xres" "yres" "xoffset" "yoffset"})

(defn assoc-field
  ([image field-name value]
   (assoc-field image field-name value {}))
  ([image field-name value opts]
   (let [field-name (str field-name)]
     (if (contains? copy-header-fields field-name)
       (call! "copy" {:in                  (runtime/image-handle image)
                      (keyword field-name) value})
       (let [copy (copy-image image)]
         (try
           (runtime/image-assoc-field! copy field-name value opts)
           copy
           (catch Throwable t
             (close-quietly copy)
             (throw t))))))))

(defn update-field
  [image field-name f & args]
  (assoc-field image field-name (apply f (field image field-name) args)))

(defn dissoc-field
  [image field-name]
  (let [copy (copy-image image)]
    (try
      (runtime/image-dissoc-field! copy field-name)
      copy
      (catch Throwable t
        (close-quietly copy)
        (throw t)))))

(defn pages
  [image]
  (field image "n-pages"))

(defn page-height
  [image]
  (field image "page-height"))

(defn page-delays
  [image]
  (field image "delay"))

(defn loop-count
  [image]
  (field image "loop"))

(defn- validate-pages
  [page-count]
  (when-not (and (integer? page-count) (pos? page-count))
    (throw (ex-info "pages must be a positive integer"
                    {:pages page-count}))))

(defn- validate-page-height
  [frame-height]
  (when-not (and (integer? frame-height) (pos? frame-height))
    (throw (ex-info "page-height must be a positive integer"
                    {:page-height frame-height}))))

(defn- validate-loop-count
  [loop-value]
  (when-not (and (integer? loop-value) (<= 0 loop-value))
    (throw (ex-info "loop count must be a non-negative integer"
                    {:loop loop-value}))))

(defn- validate-page-delays
  ([delays]
   (validate-page-delays delays nil))
  ([delays expected-count]
   (when-not (and (sequential? delays)
                  (seq delays)
                  (every? integer? delays))
     (throw (ex-info "delay must be a non-empty sequence of integers"
                     {:delay delays})))
   (when (and expected-count (not= expected-count (count delays)))
     (throw (ex-info "delay count must match page count"
                     {:delay-count (count delays)
                      :pages       expected-count})))
   (vec delays)))

(defn assoc-pages
  [image page-count]
  (validate-pages page-count)
  (assoc-field image "n-pages" page-count {:type :int}))

(defn assoc-page-height
  [image frame-height]
  (validate-page-height frame-height)
  (assoc-field image "page-height" frame-height {:type :int}))

(defn assoc-page-delays
  [image delays]
  (assoc-field image
               "delay"
               (validate-page-delays delays (pages image))
               {:type :array-int}))

(defn assoc-loop-count
  [image loop-value]
  (validate-loop-count loop-value)
  (assoc-field image "loop" loop-value {:type :int}))

(defn- frame-layout*
  [image]
  (let [image-height         (height image)
        explicit-pages       (pages image)
        explicit-page-height (page-height image)]
    (cond
      (and explicit-page-height (pos? explicit-page-height))
      (do
        (when-not (zero? (mod image-height explicit-page-height))
          (throw (ex-info "Image height must be evenly divisible by page-height"
                          {:height      image-height
                           :page-height explicit-page-height})))
        (let [derived-pages (/ image-height explicit-page-height)]
          (when (and explicit-pages (not= explicit-pages derived-pages))
            (throw (ex-info "n-pages does not match height/page-height"
                            {:height      image-height
                             :page-height explicit-page-height
                             :n-pages     explicit-pages})))
          {:pages       derived-pages
           :page-height explicit-page-height}))

      (and explicit-pages (pos? explicit-pages))
      (do
        (when-not (zero? (mod image-height explicit-pages))
          (throw (ex-info "Image height must be evenly divisible by n-pages"
                          {:height  image-height
                           :n-pages explicit-pages})))
        {:pages       explicit-pages
         :page-height (/ image-height explicit-pages)})

      :else
      {:pages 1 :page-height image-height})))

(defn- multipage?
  [image]
  (> (:pages (frame-layout* image)) 1))

(defn- split-frames
  [image]
  (let [{:keys [pages page-height]} (frame-layout* image)
        frame-width                 (width image)]
    (mapv (fn [idx]
            (call! "extract_area" {:input  image
                                   :left   0
                                   :top    (* idx page-height)
                                   :width  frame-width
                                   :height page-height}))
          (range pages))))

(defn- annotate-animation!
  [image {:keys [pages page-height loop delay]}]
  (validate-pages pages)
  (validate-page-height page-height)
  (runtime/image-assoc-field! image "n-pages" pages {:type :int})
  (runtime/image-assoc-field! image "page-height" page-height {:type :int})
  (when (some? loop)
    (validate-loop-count loop)
    (runtime/image-assoc-field! image "loop" loop {:type :int}))
  (when (some? delay)
    (runtime/image-assoc-field! image "delay" (validate-page-delays delay pages) {:type :array-int}))
  image)

(defn- reassemble
  [frames {:keys [loop delay]}]
  (let [frame-count  (count frames)
        frame-height (height (first frames))
        joined       (call! "arrayjoin" {:in frames :across 1})]
    (try
      (annotate-animation! joined {:pages       frame-count
                                   :page-height frame-height
                                   :loop        loop
                                   :delay       delay})
      joined
      (catch Throwable t
        (close-quietly joined)
        (throw t)))))

(defn- map-pages*
  [image f]
  (if-not (multipage? image)
    (f image)
    (let [frames      (split-frames image)
          transformed (try
                        (mapv f frames)
                        (catch Throwable t
                          (doseq [frame frames]
                            (close-quietly frame))
                          (throw t)))]
      (try
        (reassemble transformed {:loop  (loop-count image)
                                 :delay (page-delays image)})
        (finally
          (doseq [frame transformed]
            (close-quietly frame))
          (doseq [frame frames]
            (close-quietly frame)))))))

(defn extract-area-pages
  [image left top width height]
  (if-not (multipage? image)
    (call! "extract_area" {:input  image
                           :left   left
                           :top    top
                           :width  width
                           :height height})
    (map-pages* image #(call! "extract_area" {:input  %
                                              :left   left
                                              :top    top
                                              :width  width
                                              :height height}))))

(defn embed-pages
  ([image x y width height]
   (embed-pages image x y width height {}))
  ([image x y width height opts]
   (if-not (multipage? image)
     (call! "embed" (merge {:in     image
                            :x      x
                            :y      y
                            :width  width
                            :height height}
                           opts))
     (map-pages* image #(call! "embed" (merge {:in     %
                                               :x      x
                                               :y      y
                                               :width  width
                                               :height height}
                                              opts))))))

(defn rot-pages
  [image angle]
  (if-not (multipage? image)
    (call! "rot" {:in image :angle angle})
    (map-pages* image #(call! "rot" {:in % :angle angle}))))

(defn assemble-pages
  ([frames]
   (assemble-pages frames {}))
  ([frames {:keys [loop delay] :as _opts}]
   (let [frames (vec frames)]
     (when-not (seq frames)
       (throw (ex-info "assemble-pages requires at least one frame"
                       {})))
     (let [frame-width  (width (first frames))
           frame-height (height (first frames))]
       (doseq [frame frames]
         (when (or (not= frame-width (width frame))
                   (not= frame-height (height frame)))
           (throw (ex-info "All frames passed to assemble-pages must have the same size"
                           {:expected [frame-width frame-height]
                            :actual   [(width frame) (height frame)]}))))
       (when (some? delay)
         (validate-page-delays delay (count frames)))
       (when (some? loop)
         (validate-loop-count loop))
       (reassemble frames {:loop  loop
                           :delay delay})))))
