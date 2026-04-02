(ns ol.vips.types
  (:require
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private supported-primitive-kinds
  #{:string :boolean :int :uint :long :int64 :uint64 :double :enum :flags})

(defn- kebab-name
  [value]
  (-> value
      (str/replace "_" "-")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case))

(defn enum-id
  [type-name]
  (-> type-name
      (str/replace #"^Vips" "")
      kebab-name
      keyword))

(defn- enum-reference
  [type-name]
  (str "ol.vips.enums/" (name (enum-id type-name))))

(defn- image-type?
  [{:keys [value-type]}]
  (= "VipsImage" value-type))

(defn- image-seq-type?
  [{:keys [value-type]}]
  (= "VipsArrayImage" value-type))

(defn supported-input?
  [{:keys [kind] :as arg}]
  (case kind
    :object (image-type? arg)
    :boxed (image-seq-type? arg)
    (contains? supported-primitive-kinds kind)))

(defn supported-output?
  [{:keys [kind] :as arg}]
  (case kind
    :object (image-type? arg)
    (contains? supported-primitive-kinds kind)))

(defn public-type
  [{:keys [kind value-type]}]
  (cond
    (= kind :object)
    {:kind  :image
     :label "image"}

    (= kind :boxed)
    {:kind  :image-seq
     :label "seqable of image"}

    (= kind :string)
    {:kind  :string
     :label "string"}

    (= kind :boolean)
    {:kind  :boolean
     :label "boolean"}

    (contains? #{:int :uint :long :int64 :uint64} kind)
    {:kind  :integer
     :label "integer"}

    (= kind :double)
    {:kind  :float
     :label "float"}

    (= kind :enum)
    {:kind      :enum
     :label     "keyword"
     :enum-id   (enum-id value-type)
     :reference (enum-reference value-type)}

    (= kind :flags)
    {:kind  :flags
     :label "integer flags"}

    :else
    {:kind  :unknown
     :label "value"}))

(defn public-arg-spec
  [{:keys [name blurb] :as arg}]
  {:name  name
   :blurb blurb
   :type  (public-type arg)})
