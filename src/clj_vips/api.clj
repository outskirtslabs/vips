(ns clj-vips.api
  "These function map directly to libvips' C API."
  (:require
   [clojure.java.io :as io]
   [coffi.mem :as mem]
   [coffi.ffi :as ffi :refer [defcfn]]))

(let [arch (System/getProperty "os.arch")]
  (try
    (ffi/load-system-library "vips")
    (catch Throwable _
      (ex-info (str "Architecture not supported: " arch)
               {:arch arch}))))
