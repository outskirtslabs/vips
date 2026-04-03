(ns ^:no-doc ol.vips.impl.image
  (:require
   [ol.vips.impl.api :as api]
   [ol.vips.impl.introspect :as introspect]))

(set! *warn-on-reflection* true)

(def ^:private copy-header-fields
  #{"width" "height" "bands" "format" "coding" "interpretation"
    "xres" "yres" "xoffset" "yoffset"})

(defn metadata
  [image]
  (let [base (api/image-info image)]
    (cond-> base
      (some? (api/image-field image "n-pages"))
      (assoc :pages (api/image-field image "n-pages"))

      (some? (api/image-field image "page-height"))
      (assoc :page-height (api/image-field image "page-height"))

      (some? (api/image-field image "loop"))
      (assoc :loop (api/image-field image "loop"))

      (some? (api/image-field image "delay"))
      (assoc :delay (api/image-field image "delay")))))

(defn copy-image
  [image]
  (introspect/call-operation "copy" {:in image}))

(defn assoc-field
  ([image field-name value]
   (assoc-field image field-name value {}))
  ([image field-name value opts]
   (let [field-name (str field-name)]
     (if (contains? copy-header-fields field-name)
       (introspect/call-operation "copy" {:in                  (api/image-handle image)
                                          (keyword field-name) value})
       (let [copy (copy-image image)]
         (try
           (api/image-assoc-field! copy field-name value opts)
           copy
           (catch Throwable t
             (api/close-quietly copy)
             (throw t))))))))

(defn update-field
  [image field-name f & args]
  (assoc-field image field-name (apply f (api/image-field image field-name) args)))

(defn dissoc-field
  [image field-name]
  (let [copy (copy-image image)]
    (try
      (api/image-dissoc-field! copy field-name)
      copy
      (catch Throwable t
        (api/close-quietly copy)
        (throw t)))))

(defn pages
  [image]
  (api/image-field image "n-pages"))

(defn page-height
  [image]
  (api/image-field image "page-height"))

(defn page-delays
  [image]
  (api/image-field image "delay"))

(defn loop-count
  [image]
  (api/image-field image "loop"))

(defn validate-pages
  [page-count]
  (when-not (and (integer? page-count) (pos? page-count))
    (throw (ex-info "pages must be a positive integer"
                    {:pages page-count}))))

(defn validate-page-height
  [frame-height]
  (when-not (and (integer? frame-height) (pos? frame-height))
    (throw (ex-info "page-height must be a positive integer"
                    {:page-height frame-height}))))

(defn validate-loop-count
  [loop-value]
  (when-not (and (integer? loop-value) (<= 0 loop-value))
    (throw (ex-info "loop count must be a non-negative integer"
                    {:loop loop-value}))))

(defn validate-page-delays
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

(defn frame-layout*
  [image]
  (let [image-height         (api/image-height image)
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

(defn multipage?
  [image]
  (> (:pages (frame-layout* image)) 1))

(defn split-frames
  [image]
  (let [{:keys [pages page-height]} (frame-layout* image)
        frame-width                 (api/image-width image)]
    (mapv (fn [idx]
            (introspect/call-operation "extract_area" {:input  image
                                                       :left   0
                                                       :top    (* idx page-height)
                                                       :width  frame-width
                                                       :height page-height}))
          (range pages))))

(defn annotate-animation!
  [image {:keys [pages page-height loop delay]}]
  (validate-pages pages)
  (validate-page-height page-height)
  (api/image-assoc-field! image "n-pages" pages {:type :int})
  (api/image-assoc-field! image "page-height" page-height {:type :int})
  (when (some? loop)
    (validate-loop-count loop)
    (api/image-assoc-field! image "loop" loop {:type :int}))
  (when (some? delay)
    (api/image-assoc-field! image "delay" (validate-page-delays delay pages) {:type :array-int}))
  image)

(defn reassemble
  [frames {:keys [loop delay]}]
  (let [frame-count  (count frames)
        frame-height (api/image-height (first frames))
        joined       (introspect/call-operation "arrayjoin" {:in frames :across 1})]
    (try
      (annotate-animation! joined {:pages       frame-count
                                   :page-height frame-height
                                   :loop        loop
                                   :delay       delay})
      joined
      (catch Throwable t
        (api/close-quietly joined)
        (throw t)))))

(defn map-pages*
  [image f]
  (if-not (multipage? image)
    (f image)
    (let [frames      (split-frames image)
          transformed (try
                        (mapv f frames)
                        (catch Throwable t
                          (doseq [frame frames]
                            (api/close-quietly frame))
                          (throw t)))]
      (try
        (reassemble transformed {:loop  (loop-count image)
                                 :delay (page-delays image)})
        (finally
          (doseq [frame transformed]
            (api/close-quietly frame))
          (doseq [frame frames]
            (api/close-quietly frame)))))))

(defn extract-area-pages
  [image left top width height]
  (if-not (multipage? image)
    (introspect/call-operation "extract_area" {:input  image
                                               :left   left
                                               :top    top
                                               :width  width
                                               :height height})
    (map-pages* image #(introspect/call-operation "extract_area" {:input  %
                                                                  :left   left
                                                                  :top    top
                                                                  :width  width
                                                                  :height height}))))

(defn embed-pages
  ([image x y width height]
   (embed-pages image x y width height {}))
  ([image x y width height opts]
   (if-not (multipage? image)
     (introspect/call-operation "embed" (merge {:in     image
                                                :x      x
                                                :y      y
                                                :width  width
                                                :height height}
                                               opts))
     (map-pages* image #(introspect/call-operation "embed" (merge {:in     %
                                                                   :x      x
                                                                   :y      y
                                                                   :width  width
                                                                   :height height}
                                                                  opts))))))

(defn rot-pages
  [image angle]
  (if-not (multipage? image)
    (introspect/call-operation "rot" {:in image :angle angle})
    (map-pages* image #(introspect/call-operation "rot" {:in % :angle angle}))))

(defn assemble-pages
  ([frames]
   (assemble-pages frames {}))
  ([frames {:keys [loop delay] :as _opts}]
   (let [frames (vec frames)]
     (when-not (seq frames)
       (throw (ex-info "assemble-pages requires at least one frame"
                       {})))
     (let [frame-width  (api/image-width (first frames))
           frame-height (api/image-height (first frames))]
       (doseq [frame frames]
         (when (or (not= frame-width (api/image-width frame))
                   (not= frame-height (api/image-height frame)))
           (throw (ex-info "All frames passed to assemble-pages must have the same size"
                           {:expected [frame-width frame-height]
                            :actual   [(api/image-width frame) (api/image-height frame)]}))))
       (when (some? delay)
         (validate-page-delays delay (count frames)))
       (when (some? loop)
         (validate-loop-count loop))
       (reassemble frames {:loop  loop
                           :delay delay})))))
