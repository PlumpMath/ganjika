(ns ganjika.core)

(defn- camel-to-kebab-case [name]
  "Converts from camel to kebab case"
  (let [uppercase-to-hyphened-lowercase
        #(if (Character/isUpperCase %)
           (str "-" (Character/toLowerCase %)) %)]
    (reduce
     #(str %1 (uppercase-to-hyphened-lowercase %2))
     "" name)))

(defn- method-spec [m]
  {:name (camel-to-kebab-case (.getName m))
   :raw-name (.getName m)
   :args (.getParameterCount m)
   :is-void (-> m
                 .getReturnType
                 .getName
                 (= "void"))})

(defn- declare-fn
  "Given a method spec create an unevaluated function form, currying
  over the provided instance"
  [spec instance no-currying]
  (let [method-name (symbol (:name spec))
        raw-method-name (symbol (str "." (:raw-name spec)))
        params (vec (map gensym (range (:args spec))))
        is-void (:is-void spec)]
    (if no-currying
      `(defn ~method-name [this# ~@params]
         (let [return# (~raw-method-name this# ~@params)]
           (if ~is-void
             this#
             return#)))
      `(defn ~method-name [~@params]
         (let [return# (~raw-method-name @~instance ~@params)]
           (if ~is-void
             @~instance
             return#))))))

(defn- define-symbol
  "Creates and return a symbol that resolves to the provided object"
  [object]
  (let [class-name (.getSimpleName (class object))
        obj-symbol (gensym class-name)]
    (eval `(def ~obj-symbol ~object))))

(defmacro def-java-methods
  "Maps the provided provided Java class or object methods to
  clojure-like functions (inside using-ns if any or in the current
  namespace). If target is a class an instance is created (assumes
  default constructor) unless currying is disabled (via :currying
  false)."
  [target & {:keys [using-ns currying]}]
  (let [no-currying (false? currying)
        target (eval target)
        [clazz instance] (cond
                          (and (class? target) no-currying) [target nil]
                          no-currying [(class target) nil]
                          (class? target) [target (.newInstance target)]
                          :else [(class target) target])
        specs (map method-spec (.getDeclaredMethods clazz))
        target-sym (if-not no-currying (define-symbol instance))
        current-ns (.getName *ns*)]
    (when-not no-currying (assert (instance? clazz instance)))
    `(do
       (when ~using-ns
         (in-ns ~using-ns))
       ~@(map #(declare-fn % target-sym no-currying) specs)
       (in-ns (quote ~current-ns)))))

(comment
  ;; multiple arity
  (refer 'fus.noma.da :only ['mouse-move])
  (to-clj java.awt.Robot :using-ns 'fus.noma.da))