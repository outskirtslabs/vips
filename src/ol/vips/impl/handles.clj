(ns ^{:no-doc true :clj-reload/no-reload true} ol.vips.impl.handles)

(set! *warn-on-reflection* true)

(defonce ^:private definitions-loaded? (atom false))

;; Reloading deftype creates new classes, even inside defonce.
;; Load the type definitions once so existing images still work after a reload.
;; Always load them during AOT compilation to write the class files.
(when (or *compile-files* (not @definitions-loaded?))
  (load "/ol/vips/impl/handles/types")
  (reset! definitions-loaded? true))
