(ns ganjika.core
  (:require [ganjika.util :refer [camel-to-kebab-case is-public
                                  map-values]]
            [ganjika.coercion :refer [primitives]]))

(defn- method-specs
  "Creates a seq of specs for all public methods of the provided class."
  [^java.lang.Class c]
  (->> c
       .getDeclaredMethods
       (filter is-public)
       (map (fn [m]
              {:class-name (.getName c)
               :name (camel-to-kebab-case (.getName m))
               :raw-name (.getName m)
               :param-count (.getParameterCount m)
               :is-void (-> m
                            .getReturnType
                            .getName
                            (= "void"))
               :param-types (vec (.getParameterTypes m))
               :is-static (java.lang.reflect.Modifier/isStatic (.getModifiers m))}))))

(defn- hint-symbol
  "Hints the provided symbol using the provided type.
  For primitive types uses the Java wrapper classes."
  [sym type]
  (vary-meta sym assoc :tag (or (primitives (.getName type)) type)))

(defn- build-params
  "Given a method spec returns a list of symbols to be used as function
  parameters (type-hinting them if necessary)"
  [spec use-type-hinting symbol-prefix]
  (let [params (->> spec
                    :param-count
                    range
                    (map #(gensym (str (name symbol-prefix) %)))
                    vec)]
    (if (and use-type-hinting (not (:disable-hinting spec)))
      (map hint-symbol params (:param-types spec))
      params)))

(defn- apply-coercing
  "Returns an unevaluated apply of method-symbol coercing the provided
  params if necessary."
  [method-symbol instance params signatures]
  (let [instance (if (= instance :static) '() (list instance))
        coerced-params (map (fn [_] (gensym)) params)]
    `(apply (fn [~@coerced-params] (~method-symbol
                                   ~@instance
                                   ~@coerced-params))
            (if (not (empty? (list ~@params)))
              (ganjika.coercion/coerce-params
               (list ~@params)
               (list ~@signatures))
              '()))))

(defn- apply-without-coercing
  "Returns an unevaluated application of method-symbol"
  [method-symbol instance params signatures]
  (let [instance (if (= instance :static) '() (list instance))]
    `(~method-symbol ~@instance ~@params)))

(defn- build-arity
  "Giving a spec builds the arity part of a fn, i.e.: `([params] body)."
  [instance no-currying use-type-hinting type-coercion spec]
  (let [raw-method-symbol (symbol (str "." (:raw-name spec)))
        original-params (build-params spec use-type-hinting :original-param)
        signatures (:signatures spec)
        is-void (:is-void spec)
        application-strategy (if type-coercion apply-coercing apply-without-coercing)
        apply-fn (fn [method target] (application-strategy method target original-params signatures))]
    (cond
     ;; arity for static method
     (:is-static spec)
     (let [static-method-symbol (symbol (str (:class-name spec) "/" (:raw-name spec)))]
       `([~@original-params] ~(apply-fn static-method-symbol :static)))
     ;; arity for non-curried function
     no-currying
     (let [this-symbol (gensym "this")]
       `([~this-symbol ~@original-params]
         (let [result# ~(apply-fn raw-method-symbol this-symbol)]
           (if ~is-void
             ~this-symbol
             result#))))
     ;; arity for curried function
     :else
     `([~@original-params]
       (let [result# ~(apply-fn raw-method-symbol `@~instance)]
         (if ~is-void
           @~instance
           result#))))))

(defn- build-function
  "Builds a fn definition with n arities, where n is (count specs).
  The function will curry the target-sym unless no-currying is true.  If
  type-hinting is true, type-hinted all the function parameters (unless
  the spec forbids it with :disable-hinting)."
  [target-sym no-currying type-hinting type-coercion [fn-name specs]]
  (let [fn-symbol (symbol fn-name)
        arity-fn (partial build-arity target-sym no-currying type-hinting type-coercion)]
    `(defn ~fn-symbol ~@(map arity-fn specs))))

(defn- define-symbol
  "Creates and return a symbol that resolves to the provided object"
  [object]
  (let [class-name (.getSimpleName (class object))
        obj-symbol (gensym class-name)]
    (eval `(def ~obj-symbol ~object))))

(defn- remove-repeated-arities
  "Given a seq of specs with the same :name, removes the ones that have
  repeated arities."
  [specs]
  (map (fn [[_ [spec & has-specs-with-same-arity]]]
         (assoc spec
           :disable-hinting (not (empty? has-specs-with-same-arity))
           :signatures (map :param-types (cons spec has-specs-with-same-arity))))
       (group-by :param-count specs)))

(defn- build-specs
  "Given a class builds map of method specs grouped by :name"
  [clazz]
  (->> clazz
       method-specs
       (group-by :name)
       (map-values remove-repeated-arities)))

(defmacro def-java-fns
  "Maps the provided provided object methods to clojure-like
  functions (inside using-ns if any or in the current namespace). All
  fns are curried with the provided object unless currying is disabled
  via :currying false; when currying is disabled target must be a
  class."
  [target & {:keys [using-ns currying disable-type-hinting disable-coercion]}]
  {:pre [(some? target), ;; target can't be null
         (if (false? currying) ;; if no currying, target must be a class
           (instance? java.lang.Class (eval target))
           :dont-care)]}
  (let [no-currying (false? currying)
        type-hinting (not (true? disable-type-hinting))
        type-coercion (not (true? disable-coercion))
        resolved-target (eval target)
        clazz (if no-currying resolved-target (class resolved-target))
        instance (if no-currying nil resolved-target)
        specs (build-specs clazz)
        target-sym (if no-currying clazz (define-symbol instance))
        current-ns (.getName *ns*)
        function-builder (partial build-function target-sym no-currying type-hinting type-coercion)
        mappings (map-values #(:raw-name (first %)) specs)]
    (when-not no-currying (assert (instance? clazz instance)))
    `(do
       (require 'ganjika.coercion)
       (when ~using-ns
         (in-ns ~using-ns))
       ~@(map function-builder specs)
       (in-ns (quote ~current-ns))
       ~mappings)))
