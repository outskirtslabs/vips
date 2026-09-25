(in-ns 'ol.vips.impl.handles)

(import '[java.lang.foreign Arena]
        '[java.util.concurrent.atomic AtomicBoolean AtomicReference])

(defprotocol PointerBacked
  (pointer ^java.lang.foreign.MemorySegment [this]))

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

(deftype ImageHandle [ptr ^AtomicBoolean closed? keeper unref!]
  PointerBacked
  (pointer [_]
    (if (.get closed?)
      (throw (ex-info "Cannot use closed image handle"
                      {:type :ol.vips/closed-image-handle}))
      ptr))

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      (unref! ptr)
      (when keeper
        (.close ^java.lang.AutoCloseable keeper))))

  Object
  (toString [_]
    (str "#<ol.vips.impl.handles.ImageHandle " ptr ">")))

(deftype StreamBridge [ptr ^Arena arena stream callbacks ^AtomicReference failure-ref close-stream! ^AtomicBoolean closed? unref!]
  PointerBacked
  (pointer [_] ptr)

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed? false true)
      (try
        (unref! ptr)
        (finally
          (try
            (close-stream!)
            (finally
              (.close arena)))))))

  Object
  (toString [_]
    (str "#<ol.vips.impl.handles.StreamBridge " ptr ">")))

(defn stream-failure-ref
  [^StreamBridge bridge]
  (.failure-ref bridge))
