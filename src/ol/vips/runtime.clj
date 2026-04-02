(ns ol.vips.runtime
  (:require
   [clojure.string :as str]
   [coffi.ffi :as ffi]
   [coffi.layout :as layout]
   [coffi.mem :as mem]
   [ol.vips.native.loader :as loader])
  (:import
   [java.io File]
   [java.io InputStream]
   [java.lang.foreign Arena Linker SymbolLookup]
   [java.nio.file Path]
   [java.util.concurrent.atomic AtomicBoolean]))

(set! *warn-on-reflection* true)

(mem/defalias ::g-type ::mem/long)
(mem/defalias ::size-t ::mem/long)
(mem/defalias ::ssize-t ::mem/long)

(mem/defalias ::g-value
  (layout/with-c-layout
    [::mem/struct
     [[:g-type ::mem/long]
      [:data [::mem/array ::mem/long 2]]]]))

(declare bindings)

(defprotocol PointerBacked
  (pointer ^java.lang.foreign.MemorySegment [this]))

(declare image-handle)

(deftype OperationResult [result-map ^AtomicBoolean closed?]
  clojure.lang.ILookup
  (valAt [_ key]
    (get result-map key))
  (valAt [_ key not-found]
    (get result-map key not-found))

  clojure.lang.Associative
  (assoc [_ key value]
    (assoc result-map key value))
  (containsKey [_ key]
    (contains? result-map key))
  (entryAt [_ key]
    (find result-map key))

  clojure.lang.IPersistentMap
  (without [_ key]
    (dissoc result-map key))

  clojure.lang.Seqable
  (seq [_]
    (seq result-map))

  clojure.lang.Counted
  (count [_]
    (count result-map))

  clojure.lang.IPersistentCollection
  (cons [_ entry]
    (cons entry result-map))
  (empty [_]
    {})
  (equiv [_ other]
    (= result-map other))

  java.lang.Iterable
  (iterator [_]
    (.iterator ^Iterable result-map))

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      (doseq [value (vals result-map)]
        (when (instance? java.lang.AutoCloseable value)
          (.close ^java.lang.AutoCloseable value)))))

  Object
  (equals [_ other]
    (= result-map other))
  (hashCode [_]
    (hash result-map))
  (toString [_]
    (str result-map)))

(deftype ChunkStream [seq* close-fn]
  clojure.lang.Seqable
  (seq [_]
    (seq @seq*))

  java.lang.AutoCloseable
  (close [_]
    (close-fn))

  Object
  (toString [_]
    "#<ol.vips.runtime.ChunkStream>"))

(deftype ImageHandle [ptr ^AtomicBoolean closed? keeper]
  PointerBacked
  (pointer [_] ptr)

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      ((bindings :g-object-unref) ptr)
      (when keeper
        (.close ^java.lang.AutoCloseable keeper))))

  Object
  (toString [_]
    (str "#<ol.vips.runtime.ImageHandle " ptr ">")))

(def ^:private native-symbol-specs
  {:g-free                     ["g_free" [::mem/pointer] ::mem/void]
   :g-object-ref               ["g_object_ref" [::mem/pointer] ::mem/pointer]
   :g-object-unref             ["g_object_unref" [::mem/pointer] ::mem/void]
   :g-object-get-property      ["g_object_get_property"
                                [::mem/pointer ::mem/c-string ::mem/pointer]
                                ::mem/void]
   :g-object-set-property      ["g_object_set_property"
                                [::mem/pointer ::mem/c-string ::mem/pointer]
                                ::mem/void]
   :g-type-children            ["g_type_children" [::g-type ::mem/pointer] ::mem/pointer]
   :g-type-class-ref           ["g_type_class_ref" [::g-type] ::mem/pointer]
   :g-type-class-unref         ["g_type_class_unref" [::mem/pointer] ::mem/void]
   :g-type-fundamental         ["g_type_fundamental" [::g-type] ::g-type]
   :g-type-from-name           ["g_type_from_name" [::mem/c-string] ::g-type]
   :g-type-name                ["g_type_name" [::g-type] ::mem/c-string]
   :param-spec-get-blurb       ["g_param_spec_get_blurb" [::mem/pointer] ::mem/c-string]
   :param-spec-get-name        ["g_param_spec_get_name" [::mem/pointer] ::mem/c-string]
   :nickname-find              ["vips_nickname_find" [::g-type] ::mem/c-string]
   :argument-map               ["vips_argument_map"
                                [::mem/pointer
                                 [::ffi/fn [::mem/pointer
                                            ::mem/pointer
                                            ::mem/pointer
                                            ::mem/pointer
                                            ::mem/pointer
                                            ::mem/pointer]
                                  ::mem/pointer]
                                 ::mem/pointer
                                 ::mem/pointer]
                                ::mem/pointer]
   :type-map-all               ["vips_type_map_all"
                                [::g-type
                                 [::ffi/fn [::g-type ::mem/pointer] ::mem/pointer]
                                 ::mem/pointer]
                                ::mem/pointer]
   :g-value-get-boolean        ["g_value_get_boolean" [::mem/pointer] ::mem/int]
   :g-value-get-double         ["g_value_get_double" [::mem/pointer] ::mem/double]
   :g-value-get-enum           ["g_value_get_enum" [::mem/pointer] ::mem/int]
   :g-value-get-flags          ["g_value_get_flags" [::mem/pointer] ::mem/int]
   :g-value-get-int            ["g_value_get_int" [::mem/pointer] ::mem/int]
   :g-value-get-int64          ["g_value_get_int64" [::mem/pointer] ::mem/long]
   :g-value-get-object         ["g_value_get_object" [::mem/pointer] ::mem/pointer]
   :g-value-get-string         ["g_value_get_string" [::mem/pointer] ::mem/c-string]
   :g-value-get-uint           ["g_value_get_uint" [::mem/pointer] ::mem/int]
   :g-value-get-uint64         ["g_value_get_uint64" [::mem/pointer] ::mem/long]
   :g-value-init               ["g_value_init" [::mem/pointer ::g-type] ::mem/pointer]
   :g-value-set-boolean        ["g_value_set_boolean" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-boxed          ["g_value_set_boxed" [::mem/pointer ::mem/pointer] ::mem/void]
   :g-value-set-double         ["g_value_set_double" [::mem/pointer ::mem/double] ::mem/void]
   :g-value-set-enum           ["g_value_set_enum" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-flags          ["g_value_set_flags" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-int            ["g_value_set_int" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-int64          ["g_value_set_int64" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-set-long           ["g_value_set_long" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-set-object         ["g_value_set_object" [::mem/pointer ::mem/pointer] ::mem/void]
   :g-value-set-string         ["g_value_set_string" [::mem/pointer ::mem/c-string] ::mem/void]
   :g-value-set-uint           ["g_value_set_uint" [::mem/pointer ::mem/int] ::mem/void]
   :g-value-set-uint64         ["g_value_set_uint64" [::mem/pointer ::mem/long] ::mem/void]
   :g-value-unset              ["g_value_unset" [::mem/pointer] ::mem/void]
   :image-get-height           ["vips_image_get_height" [::mem/pointer] ::mem/int]
   :image-get-bands            ["vips_image_get_bands" [::mem/pointer] ::mem/int]
   :image-get-type             ["vips_image_get_type" [] ::g-type]
   :image-get-width            ["vips_image_get_width" [::mem/pointer] ::mem/int]
   :image-has-alpha            ["vips_image_hasalpha" [::mem/pointer] ::mem/int]
   :image-new-from-buffer      ["vips_image_new_from_buffer"
                                [::mem/pointer ::size-t ::mem/c-string ::mem/pointer]
                                ::mem/pointer]
   :image-new-from-source      ["vips_image_new_from_source"
                                [::mem/pointer ::mem/c-string ::mem/pointer]
                                ::mem/pointer]
   :image-new-from-file        ["vips_image_new_from_file"
                                [::mem/c-string ::mem/pointer]
                                ::mem/pointer]
   :image-write-to-buffer      ["vips_image_write_to_buffer"
                                [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer ::mem/pointer]
                                ::mem/int]
   :image-write-to-file        ["vips_image_write_to_file"
                                [::mem/pointer ::mem/c-string ::mem/pointer]
                                ::mem/int]
   :image-write-to-target      ["vips_image_write_to_target"
                                [::mem/pointer ::mem/c-string ::mem/pointer ::mem/pointer]
                                ::mem/int]
   :operation-get-type         ["vips_operation_get_type" [] ::g-type]
   :operation-new              ["vips_operation_new" [::mem/c-string] ::mem/pointer]
   :array-image-get-type       ["vips_array_image_get_type" [] ::g-type]
   :array-image-new            ["vips_array_image_new" [::mem/pointer ::mem/int] ::mem/pointer]
   :area-unref                 ["vips_area_unref" [::mem/pointer] ::mem/void]
   :source-new-from-descriptor ["vips_source_new_from_descriptor" [::mem/int] ::mem/pointer]
   :target-new-to-descriptor   ["vips_target_new_to_descriptor" [::mem/int] ::mem/pointer]
   :object-get-description     ["vips_object_get_description" [::mem/pointer] ::mem/c-string]
   :object-get-arg-flags       ["vips_object_get_argument_flags" [::mem/pointer ::mem/c-string] ::mem/int]
   :object-get-arg-priority    ["vips_object_get_argument_priority" [::mem/pointer ::mem/c-string] ::mem/int]
   :object-unref-outputs       ["vips_object_unref_outputs" [::mem/pointer] ::mem/void]
   :cache-operation-build      ["vips_cache_operation_build" [::mem/pointer] ::mem/pointer]
   :vips-error-buffer          ["vips_error_buffer" [] ::mem/c-string]
   :vips-error-clear           ["vips_error_clear" [] ::mem/void]
   :vips-init                  ["vips_init" [::mem/c-string] ::mem/int]
   :vips-shutdown              ["vips_shutdown" [] ::mem/void]
   :vips-version               ["vips_version" [::mem/int] ::mem/int]
   :vips-version-string        ["vips_version_string" [] ::mem/c-string]})

(defonce ^:private state* (atom nil))

(defn- posix?
  []
  (not (str/includes? (str/lower-case (System/getProperty "os.name")) "win")))

(defn- host-symbol-specs
  []
  (when (posix?)
    {:pipe     ["pipe" [::mem/pointer] ::mem/int]
     :read-fd  ["read" [::mem/int ::mem/pointer ::size-t] ::ssize-t]
     :write-fd ["write" [::mem/int ::mem/pointer ::size-t] ::ssize-t]
     :close-fd ["close" [::mem/int] ::mem/int]}))

(defn- path->lookup
  ^SymbolLookup [^String library-path ^Arena arena]
  (SymbolLookup/libraryLookup (Path/of library-path (make-array String 0))
                              arena))

(defn- library-lookup
  [library-paths]
  (let [arena   (Arena/ofShared)
        lookups (mapv #(path->lookup % arena) library-paths)]
    {:arena  arena
     :lookup (reduce (fn [^SymbolLookup acc ^SymbolLookup lookup]
                       (.or acc lookup))
                     lookups)}))

(defn- lookup-symbol
  [^SymbolLookup lookup symbol-name]
  (or (.orElse (.find lookup symbol-name) nil)
      (throw (ex-info "Native symbol not found in loaded ol.vips libraries"
                      {:symbol symbol-name}))))

(defn- bind-symbols
  [^SymbolLookup lookup]
  (let [host-lookup (.defaultLookup (Linker/nativeLinker))]
    (merge
     (reduce-kv
      (fn [native k [symbol-name arg-types return-type]]
        (assoc native
               k
               (ffi/cfn (lookup-symbol lookup symbol-name)
                        arg-types
                        return-type)))
      {}
      native-symbol-specs)
     (reduce-kv
      (fn [native k [symbol-name arg-types return-type]]
        (assoc native
               k
               (ffi/cfn (lookup-symbol host-lookup symbol-name)
                        arg-types
                        return-type)))
      {}
      (or (host-symbol-specs) {})))))

(defn- last-error-message
  [native]
  (some-> ((:vips-error-buffer native)) not-empty))

(defn- clear-error!
  [native]
  ((:vips-error-clear native)))

(defn- preload-library-paths
  []
  (when-let [value (some-> (System/getProperty "ol.vips.native.preload")
                           str/trim
                           not-empty)]
    (->> (str/split value (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- throw-vips-error
  [native message data]
  (let [error-message (last-error-message native)]
    (clear-error! native)
    (throw (ex-info message
                    (cond-> data
                      error-message (assoc :vips/error error-message))))))

(defn- build-gtypes
  [native]
  {:boolean     ((:g-type-from-name native) "gboolean")
   :boxed       ((:g-type-from-name native) "GBoxed")
   :double      ((:g-type-from-name native) "gdouble")
   :enum        ((:g-type-from-name native) "GEnum")
   :flags       ((:g-type-from-name native) "GFlags")
   :image       ((:image-get-type native))
   :int         ((:g-type-from-name native) "gint")
   :int64       ((:g-type-from-name native) "gint64")
   :long        ((:g-type-from-name native) "glong")
   :object      ((:g-type-from-name native) "GObject")
   :operation   ((:operation-get-type native))
   :string      ((:g-type-from-name native) "gchararray")
   :uint        ((:g-type-from-name native) "guint")
   :uint64      ((:g-type-from-name native) "guint64")
   :array-image ((:array-image-get-type native))})

(defn- wrap-image
  ([ptr]
   (wrap-image ptr nil))
  ([ptr keeper]
   (when-not (mem/null? ptr)
     (ImageHandle. ptr (AtomicBoolean. false) keeper))))

(defn adopt-image
  [ptr]
  (wrap-image ptr))

(defn operation-result
  [result-map]
  (cond
    (and (= #{:out} (set (keys result-map)))
         (satisfies? PointerBacked (:out result-map)))
    (:out result-map)

    (contains? result-map :out)
    (OperationResult. result-map (AtomicBoolean. false))

    :else
    result-map))

(defn image-handle
  [value]
  (cond
    (satisfies? PointerBacked value)
    value

    (and (map? value) (contains? value :out))
    (let [image (:out value)]
      (if (satisfies? PointerBacked image)
        image
        (throw (ex-info "Operation result map does not contain an image at :out"
                        {:value value}))))

    :else
    (throw (ex-info "Expected an image handle or operation result map"
                    {:value value}))))

(defn ensure-initialized!
  []
  (or @state*
      (locking state*
        (or @state*
            (let [manifest               (loader/read-manifest)
                  extracted              (loader/extract-libraries! (loader/default-cache-root) manifest)
                  load-paths             (vec (concat (preload-library-paths) (:library-paths extracted)))
                  _                      (doseq [path load-paths]
                                           (ffi/load-library path))
                  {:keys [arena lookup]} (library-lookup load-paths)
                  exposed                (loader/expose-paths! extracted)
                  native                 (bind-symbols lookup)
                  init-code              ((:vips-init native) "ol.vips")
                  _                      (when-not (zero? init-code)
                                           (throw-vips-error native
                                                             "Failed to initialize libvips"
                                                             {:exit-code init-code}))
                  version                ((:vips-version-string native))
                  state                  (merge exposed
                                                {:bindings             native
                                                 :gtypes               (build-gtypes native)
                                                 :lookup               lookup
                                                 :lookup-arena         arena
                                                 :manifest             manifest
                                                 :primary-library-path (first (:library-paths extracted))
                                                 :version-string       version})]
              (reset! state* state)
              state)))))

(defn state
  []
  (ensure-initialized!))

(defn bindings
  ([] (:bindings (ensure-initialized!)))
  ([k] (get (bindings) k))
  ([k not-found] (get (bindings) k not-found)))

(defn streaming-supported?
  []
  (and (posix?)
       (bindings :pipe)
       (bindings :read-fd)
       (bindings :write-fd)
       (bindings :close-fd)))

(defn gtypes
  []
  (:gtypes (ensure-initialized!)))

(defn version-string
  []
  (:version-string (ensure-initialized!)))

(defn type-name
  [gtype]
  ((bindings :g-type-name) gtype))

(defn type-fundamental
  [gtype]
  ((bindings :g-type-fundamental) gtype))

(defn with-gvalue
  [gtype f]
  (with-open [arena (mem/confined-arena)]
    (let [value (mem/alloc-instance ::g-value arena)]
      ((bindings :g-value-init) value gtype)
      (try
        (f value)
        (finally
          ((bindings :g-value-unset) value))))))

(defn- close-fd!
  [fd]
  (when (and (int? fd)
             (not= -1 fd)
             (bindings :close-fd))
    ((bindings :close-fd) fd)
    nil))

(defn- pipe!
  []
  (when-not (streaming-supported?)
    (throw (ex-info "Streaming IO is not supported on this platform"
                    {:os (System/getProperty "os.name")})))
  (with-open [arena (mem/confined-arena)]
    (let [int-size (mem/size-of ::mem/int)
          fds      (mem/alloc (* 2 int-size) int-size arena)
          code     ((bindings :pipe) fds)]
      (when-not (zero? code)
        (throw (ex-info "Failed to create pipe" {:code code})))
      [(mem/read-int (mem/slice fds 0 int-size))
       (mem/read-int (mem/slice fds int-size int-size))])))

(defn open-image
  [source]
  (let [path  (str source)
        image ((bindings :image-new-from-file) path nil)]
    (when (mem/null? image)
      (throw-vips-error (bindings)
                        "Failed to open image"
                        {:source path}))
    (wrap-image image)))

(def ^:private byte-array-class
  (class (byte-array 0)))

(defn- ->byte-array
  [value]
  (cond
    (instance? byte-array-class value) value
    (sequential? value) (byte-array (map byte value))
    :else (throw (ex-info "Expected image bytes"
                          {:value value}))))

(defn- write-all!
  [fd ^bytes data]
  (with-open [arena (mem/confined-arena)]
    (let [size   (alength data)
          buffer (mem/alloc size 1 arena)]
      (mem/write-bytes buffer size data)
      (loop [offset (long 0)]
        (when (< offset size)
          (let [remaining (- size offset)
                segment   (mem/slice buffer offset remaining)
                written   (long ((bindings :write-fd) fd segment remaining))]
            (when (neg? written)
              (throw (ex-info "Failed to write to pipe" {:fd fd})))
            (recur (unchecked-add offset written))))))))

(defn- stream-reader!
  [^InputStream source fd chunk-size]
  (future
    (let [buffer (byte-array chunk-size)]
      (try
        (loop []
          (let [n (.read source buffer 0 chunk-size)]
            (when (pos? n)
              (let [chunk (byte-array n)]
                (System/arraycopy buffer 0 chunk 0 n)
                (write-all! fd chunk)
                (recur)))))
        (finally
          (.close source)
          (close-fd! fd))))))

(defn- enum-writer!
  [chunks fd]
  (future
    (try
      (doseq [chunk chunks]
        (write-all! fd (->byte-array chunk)))
      (finally
        (when (instance? java.lang.AutoCloseable chunks)
          (.close ^java.lang.AutoCloseable chunks))
        (close-fd! fd)))))

(defn- read-chunk
  [fd chunk-size]
  (with-open [arena (mem/confined-arena)]
    (let [buffer (mem/alloc chunk-size 1 arena)
          n      ((bindings :read-fd) fd buffer chunk-size)]
      (cond
        (zero? n) nil
        (neg? n) (throw (ex-info "Failed to read from pipe" {:fd fd}))
        :else (mem/read-bytes (mem/reinterpret buffer n) (int n))))))

(defn- chunk-stream
  [fd writer chunk-size]
  (let [closed? (AtomicBoolean. false)
        finish! (fn []
                  (when (.compareAndSet closed? false true)
                    (close-fd! fd)
                    @writer
                    nil))
        seq*    (delay
                  (letfn [(step []
                            (lazy-seq
                             (if (.get closed?)
                               nil
                               (if-let [chunk (read-chunk fd chunk-size)]
                                 (cons chunk (step))
                                 (do
                                   (finish!)
                                   nil)))))]
                    (step)))]
    (ChunkStream. seq* finish!)))

(defn open-image-from-buffer
  ([source]
   (open-image-from-buffer source ""))
  ([source option-string]
   (let [data   (->byte-array source)
         arena  (Arena/ofShared)
         size   (alength ^bytes data)
         buffer (mem/alloc size 1 arena)
         image  (do
                  (mem/write-bytes buffer size data)
                  ((bindings :image-new-from-buffer) buffer size option-string nil))]
     (when (mem/null? image)
       (.close arena)
       (throw-vips-error (bindings)
                         "Failed to open image from buffer"
                         {:byte-count size}))
     (wrap-image image arena))))

(defn open-image-from-stream
  ([source]
   (open-image-from-stream source "" 65536))
  ([source option-string]
   (open-image-from-stream source option-string 65536))
  ([source option-string chunk-size]
   (when-not (instance? InputStream source)
     (throw (ex-info "Expected an InputStream"
                     {:source source
                      :type   (some-> source class .getName)})))
   (let [[read-fd write-fd] (pipe!)
         source-handle      ((bindings :source-new-from-descriptor) read-fd)]
     (close-fd! read-fd)
     (when (mem/null? source-handle)
       (close-fd! write-fd)
       (throw-vips-error (bindings)
                         "Failed to create vips source from stream"
                         {}))
     (let [writer (stream-reader! source write-fd chunk-size)
           image  ((bindings :image-new-from-source) source-handle (str option-string) nil)]
       ((bindings :g-object-unref) source-handle)
       (when (mem/null? image)
         (future-cancel writer)
         (close-fd! write-fd)
         (throw-vips-error (bindings)
                           "Failed to open image from stream"
                           {}))
       (wrap-image image)))))

(defn open-image-from-enum
  ([chunks]
   (open-image-from-enum chunks ""))
  ([chunks option-string]
   (let [[read-fd write-fd] (pipe!)
         source-handle      ((bindings :source-new-from-descriptor) read-fd)]
     (close-fd! read-fd)
     (when (mem/null? source-handle)
       (close-fd! write-fd)
       (throw-vips-error (bindings)
                         "Failed to create vips source from enum"
                         {}))
     (let [writer (enum-writer! chunks write-fd)
           image  ((bindings :image-new-from-source) source-handle (str option-string) nil)]
       ((bindings :g-object-unref) source-handle)
       (when (mem/null? image)
         (future-cancel writer)
         (close-fd! write-fd)
         (throw-vips-error (bindings)
                           "Failed to open image from enum"
                           {}))
       (wrap-image image)))))

(defn write-image!
  [image sink]
  (let [path (str sink)
        code ((bindings :image-write-to-file) (pointer (image-handle image)) path nil)]
    (when-not (zero? code)
      (throw-vips-error (bindings)
                        "Failed to write image"
                        {:sink path}))
    image))

(defn write-image-to-buffer
  [image suffix]
  (with-open [arena (mem/confined-arena)]
    (let [buffer-ptr (mem/alloc-instance ::mem/pointer arena)
          size-ptr   (mem/alloc-instance ::size-t arena)
          code       ((bindings :image-write-to-buffer)
                      (pointer (image-handle image))
                      (str suffix)
                      buffer-ptr
                      size-ptr
                      nil)]
      (when-not (zero? code)
        (throw-vips-error (bindings)
                          "Failed to write image to buffer"
                          {:suffix suffix}))
      (let [output-ptr  (mem/read-address buffer-ptr)
            output-size (mem/read-long size-ptr)]
        (try
          (mem/read-bytes (mem/reinterpret output-ptr output-size) (int output-size))
          (finally
            ((bindings :g-free) output-ptr)))))))

(defn write-image-to-stream
  ([image suffix]
   (write-image-to-stream image suffix 65536))
  ([image suffix chunk-size]
   (let [[read-fd write-fd] (pipe!)
         target             ((bindings :target-new-to-descriptor) write-fd)]
     (close-fd! write-fd)
     (when (mem/null? target)
       (close-fd! read-fd)
       (throw-vips-error (bindings)
                         "Failed to create vips target for stream"
                         {}))
     (let [writer (future
                    (try
                      (let [code ((bindings :image-write-to-target)
                                  (pointer (image-handle image))
                                  (str suffix)
                                  target
                                  nil)]
                        (when-not (zero? code)
                          (throw-vips-error (bindings)
                                            "Failed to write image to stream"
                                            {:suffix suffix})))
                      (finally
                        ((bindings :g-object-unref) target))))]
       (chunk-stream read-fd writer chunk-size)))))

(defn image-width
  [image]
  ((bindings :image-get-width) (pointer (image-handle image))))

(defn image-height
  [image]
  ((bindings :image-get-height) (pointer (image-handle image))))

(defn image-bands
  [image]
  ((bindings :image-get-bands) (pointer (image-handle image))))

(defn image-has-alpha?
  [image]
  (not (zero? ((bindings :image-has-alpha) (pointer (image-handle image))))))

(defn image-info
  [image]
  {:width      (image-width image)
   :height     (image-height image)
   :bands      (image-bands image)
   :has-alpha? (image-has-alpha? image)})
