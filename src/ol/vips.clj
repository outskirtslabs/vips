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

(defn write-to-buffer
  ([image suffix]
   (runtime/write-image-to-buffer image suffix))
  ([image suffix opts]
   (runtime/write-image-to-buffer image (append-options suffix opts))))

(defn info
  [image]
  (runtime/image-info image))

(defn image-info
  [image]
  (info image))

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
