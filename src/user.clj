(ns user
  "Development utilities and operation discovery tools"
  (:require
   [clj-vips.api :as api]
   [coffi.mem :as mem]
   [coffi.ffi :as ffi :refer [defcfn]]))

;; =============================================================================
;; Operation Discovery Tools (for development use)
;; =============================================================================

(defcfn type-map-all
  "vips_type_map_all" [::api/GType
                       [::ffi/fn [::api/GType ::mem/pointer] ::mem/pointer]
                       ::mem/pointer] ::mem/pointer)

(defcfn nickname-find
  "vips_nickname_find" [::api/GType] ::mem/c-string)

(defn op-list-callback
  "Extract the operation name and add it to our collection"
  [a g-type-int user-data]
  (let [operation-name (nickname-find g-type-int)]
    (when operation-name
      (vswap! a conj operation-name)
      (println "Found operation:" operation-name)))
  mem/null)

(defn operation-list
  "List all available vips operations"
  []
  (let [callback op-list-callback
        data (volatile! [])]
    (type-map-all (api/operation-get-type)
                  (partial callback data)
                  mem/null)
    (println "Got all")
    (prn (sort @data))))

(defn try-operation-list
  "Try listing operations with proper vips init/shutdown"
  []
  (try
    (api/vips-init "clj-vips")
    (operation-list)
    (finally
      (api/vips-shutdown))))

(comment
  ;; Use these functions for development:
  (try-operation-list))