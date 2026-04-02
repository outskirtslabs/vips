(ns ol.vips
  (:require
   [ol.vips.introspect :as introspect]
   [ol.vips.runtime :as runtime]))

(set! *warn-on-reflection* true)

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

(defn image-from-file
  [path]
  (runtime/open-image path))

(defn image-info
  [image]
  (runtime/image-info image))

(defn call!
  [operation-name opts]
  (introspect/call-operation operation-name opts))
