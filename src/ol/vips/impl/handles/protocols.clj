(ns ^{:no-doc true :clj-reload/no-reload true} ol.vips.impl.handles.protocols)

(defprotocol PointerBacked
  (retain-pointer [this]
    "Returns an owned native reference. The caller must release it."))
