(ns ^{:no-doc true :clj-reload/no-reload true} ol.vips.impl.handles.protocols)

(defprotocol PointerBacked
  (pointer [this]))

(defprotocol StreamFailureBacked
  (stream-failure-ref [this]))
