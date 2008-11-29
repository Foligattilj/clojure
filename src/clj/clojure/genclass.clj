;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Common Public License 1.0 (http://opensource.org/licenses/cpl.php)
;   which can be found in the file CPL.TXT at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(in-ns 'clojure.core)

(import '(java.lang.reflect Modifier Constructor)
        '(clojure.asm ClassWriter ClassVisitor Opcodes Type)
        '(clojure.asm.commons Method GeneratorAdapter)
        '(clojure.lang IPersistentMap))

;(defn method-sig [#^java.lang.reflect.Method meth]
;  [(. meth (getName)) (seq (. meth (getParameterTypes)))])

(defn- non-private-methods [#^Class c]
  (loop [mm {}
         considered #{}
         c c]
    (if c
      (let [[mm considered]
            (loop [mm mm
                   considered considered
                   meths (concat
                          (seq (. c (getDeclaredMethods)))
                          (seq (. c (getMethods))))]
              (if meths
                (let [#^java.lang.reflect.Method meth (first meths)
                      mods (. meth (getModifiers))
                      mk (method-sig meth)]
                  (if (or (considered mk)
                          (. Modifier (isPrivate mods))
                          (. Modifier (isStatic mods))
                          (. Modifier (isFinal mods))
                          (= "finalize" (.getName meth)))
                    (recur mm (conj considered mk) (rest meths))
                    (recur (assoc mm mk meth) (conj considered mk) (rest meths))))
                [mm considered]))]
        (recur mm considered (. c (getSuperclass))))
      mm)))

(defn- ctor-sigs [super]
  (for [#^Constructor ctor (. super (getDeclaredConstructors))
        :when (not (. Modifier (isPrivate (. ctor (getModifiers)))))]
    (apply vector (. ctor (getParameterTypes)))))

(defn- escape-class-name [c]
  (.. (.getSimpleName c) 
      (replace "[]" "<>")))

(defn- overload-name [mname pclasses]
  (if (seq pclasses)
    (apply str mname (interleave (repeat \-) 
                                 (map escape-class-name pclasses)))
    (str mname "-void")))

;(distinct (map first(keys (mapcat non-private-methods [Object IPersistentMap]))))

(def #^{:private true} prim->class
     {'int Integer/TYPE
      'long Long/TYPE
      'float Float/TYPE
      'double Double/TYPE
      'void Void/TYPE
      'short Short/TYPE
      'boolean Boolean/TYPE
      'byte Byte/TYPE
      'char Character/TYPE})

(defn- the-class [x] 
  (cond 
   (class? x) x
   (contains? prim->class x) (prim->class x)
   :else (let [strx (str x)]
           (clojure.lang.RT/classForName 
            (if (some #{\.} strx)
              strx
              (str "java.lang." strx))))))

(defn generate-class [options-map]
  (let [default-options {:prefix "-" :load-impl-ns true :impl-ns (ns-name *ns*)}
        {:keys [name extends implements constructors methods main factory state init exposes 
                prefix load-impl-ns impl-ns]} 
          (merge default-options options-map)
        name (str name)
        super (if extends (the-class extends) Object)
        interfaces (map the-class implements)
        supers (cons super interfaces)
        ctor-sig-map (or constructors (zipmap (ctor-sigs super) (ctor-sigs super)))
        cv (new ClassWriter (. ClassWriter COMPUTE_MAXS))
        cname (. name (replace "." "/"))
        pkg-name name
        impl-pkg-name (str impl-ns)
        impl-cname (.. impl-pkg-name (replace "." "/") (replace \- \_))
        ctype (. Type (getObjectType cname))
        iname (fn [c] (.. Type (getType c) (getInternalName)))
        totype (fn [c] (. Type (getType c)))
        to-types (fn [cs] (if (pos? (count cs))
                            (into-array (map totype cs))
                            (make-array Type 0)))
        obj-type (totype Object)
        arg-types (fn [n] (if (pos? n)
                            (into-array (replicate n obj-type))
                            (make-array Type 0)))
        super-type (totype super)
        init-name (str init)
        factory-name (str factory)
        state-name (str state)
        main-name "main"
        var-name (fn [s] (str s "__var"))
        class-type  (totype Class)
        rt-type  (totype clojure.lang.RT)
        var-type  (totype clojure.lang.Var)
        ifn-type (totype clojure.lang.IFn)
        iseq-type (totype clojure.lang.ISeq)
        ex-type  (totype java.lang.UnsupportedOperationException)
        all-sigs (distinct (concat (map #(let[[m p] (key %)] {m [p]}) (mapcat non-private-methods supers))
                                   (map (fn [[m p]] {(str m) [p]}) methods)))
        sigs-by-name (apply merge-with concat {} all-sigs)
        overloads (into {} (filter (fn [[m s]] (rest s)) sigs-by-name))
        var-fields (concat (when init [init-name]) 
                           (when main [main-name])
                           (distinct (concat (keys sigs-by-name)
                                             (mapcat (fn [[m s]] (map #(overload-name m %) s)) overloads)
                                             (mapcat (comp (partial map str) vals val) exposes))))
        emit-get-var (fn [gen v]
                       (let [false-label (. gen newLabel)
                             end-label (. gen newLabel)]
                         (. gen getStatic ctype (var-name v) var-type)
                         (. gen dup)
                         (. gen invokeVirtual var-type (. Method (getMethod "boolean isBound()")))
                         (. gen ifZCmp (. GeneratorAdapter EQ) false-label)
                         (. gen invokeVirtual var-type (. Method (getMethod "Object get()")))
                         (. gen goTo end-label)
                         (. gen mark false-label)
                         (. gen pop)
                         (. gen visitInsn (. Opcodes ACONST_NULL))
                         (. gen mark end-label)))
        emit-unsupported (fn [gen m]
                           (. gen (throwException ex-type (str (. m (getName)) " ("
                                                               impl-pkg-name "/" prefix (.getName m)
                                                               " not defined?)"))))
        emit-forwarding-method
        (fn [mname pclasses rclass as-static else-gen]
          (let [pclasses (map the-class pclasses)
                rclass (the-class rclass)
                ptypes (to-types pclasses)
                rtype (totype rclass)
                m (new Method mname rtype ptypes)
                is-overload (overloads mname)
                gen (new GeneratorAdapter (+ (. Opcodes ACC_PUBLIC) (if as-static (. Opcodes ACC_STATIC) 0)) 
                         m nil nil cv)
                found-label (. gen (newLabel))
                else-label (. gen (newLabel))
                end-label (. gen (newLabel))]
            (. gen (visitCode))
            (if (> (count pclasses) 18)
              (else-gen gen m)
              (do
                (when is-overload
                  (emit-get-var gen (overload-name mname pclasses))
                  (. gen (dup))
                  (. gen (ifNonNull found-label))
                  (. gen (pop)))
                (emit-get-var gen mname)
                (. gen (dup))
                (. gen (ifNull else-label))
                (when is-overload
                  (. gen (mark found-label)))
                                        ;if found
                (when-not as-static
                  (. gen (loadThis)))
                                        ;box args
                (dotimes [i (count ptypes)]
                  (. gen (loadArg i))
                  (. clojure.lang.Compiler$HostExpr (emitBoxReturn nil gen (nth pclasses i))))
                                        ;call fn
                (. gen (invokeInterface ifn-type (new Method "invoke" obj-type 
                                                      (to-types (replicate (+ (count ptypes)
                                                                              (if as-static 0 1)) 
                                                                           Object)))))
                                        ;(into-array (cons obj-type 
                                        ;                 (replicate (count ptypes) obj-type))))))
                                        ;unbox return
                (. gen (unbox rtype))
                (when (= (. rtype (getSort)) (. Type VOID))
                  (. gen (pop)))
                (. gen (goTo end-label))
                
                                        ;else call supplied alternative generator
                (. gen (mark else-label))
                (. gen (pop))
                
                (else-gen gen m)
            
                (. gen (mark end-label))))
            (. gen (returnValue))
            (. gen (endMethod))))
        ]
                                        ;start class definition
    (. cv (visit (. Opcodes V1_5) (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_SUPER))
                 cname nil (iname super)
                 (when interfaces
                   (into-array (map iname interfaces)))))
    
                                        ;static fields for vars
    (doseq [v var-fields]
      (. cv (visitField (+ (. Opcodes ACC_PRIVATE) (. Opcodes ACC_FINAL) (. Opcodes ACC_STATIC))
                        (var-name v) 
                        (. var-type getDescriptor)
                        nil nil)))
    
                                        ;instance field for state
    (when state
      (. cv (visitField (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_FINAL))
                        state-name 
                        (. obj-type getDescriptor)
                        nil nil)))
    
                                        ;static init to set up var fields and load init
    (let [gen (new GeneratorAdapter (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_STATIC)) 
                   (. Method getMethod "void <clinit> ()")
                   nil nil cv)]
      (. gen (visitCode))
      (doseq [v var-fields]
        (. gen push impl-pkg-name)
        (. gen push (str prefix v))
        (. gen (invokeStatic var-type (. Method (getMethod "clojure.lang.Var internPrivate(String,String)"))))
        (. gen putStatic ctype (var-name v) var-type))
      
      (when load-impl-ns
        (. gen push "clojure.core")
        (. gen push "load")
        (. gen (invokeStatic rt-type (. Method (getMethod "clojure.lang.Var var(String,String)"))))
        (. gen push (str "/" impl-cname))
        (. gen (invokeInterface ifn-type (new Method "invoke" obj-type (to-types [Object]))))
;        (. gen push (str (.replace impl-pkg-name \- \_) "__init"))
;        (. gen (invokeStatic class-type (. Method (getMethod "Class forName(String)"))))
        (. gen pop))

      (. gen (returnValue))
      (. gen (endMethod)))
    
                                        ;ctors
    (doseq [[pclasses super-pclasses] ctor-sig-map]
      (let [pclasses (map the-class pclasses)
            super-pclasses (map the-class super-pclasses)
            ptypes (to-types pclasses)
            super-ptypes (to-types super-pclasses)
            m (new Method "<init>" (. Type VOID_TYPE) ptypes)
            super-m (new Method "<init>" (. Type VOID_TYPE) super-ptypes)
            gen (new GeneratorAdapter (. Opcodes ACC_PUBLIC) m nil nil cv)
            no-init-label (. gen newLabel)
            end-label (. gen newLabel)
            nth-method (. Method (getMethod "Object nth(Object,int)"))
            local (. gen newLocal obj-type)]
        (. gen (visitCode))
        
        (if init
          (do
            (emit-get-var gen init-name)
            (. gen dup)
            (. gen ifNull no-init-label)
                                        ;box init args
            (dotimes [i (count pclasses)]
              (. gen (loadArg i))
              (. clojure.lang.Compiler$HostExpr (emitBoxReturn nil gen (nth pclasses i))))
                                        ;call init fn
            (. gen (invokeInterface ifn-type (new Method "invoke" obj-type 
                                                  (arg-types (count ptypes)))))
                                        ;expecting [[super-ctor-args] state] returned
            (. gen dup)
            (. gen push 0)
            (. gen (invokeStatic rt-type nth-method))
            (. gen storeLocal local)
            
            (. gen (loadThis))
            (. gen dupX1)
            (dotimes [i (count super-pclasses)]
              (. gen loadLocal local)
              (. gen push i)
              (. gen (invokeStatic rt-type nth-method))
              (. clojure.lang.Compiler$HostExpr (emitUnboxArg nil gen (nth super-pclasses i))))
            (. gen (invokeConstructor super-type super-m))
            
            (if state
              (do
                (. gen push 1)
                (. gen (invokeStatic rt-type nth-method))
                (. gen (putField ctype state-name obj-type)))
              (. gen pop))
            
            (. gen goTo end-label)
                                        ;no init found
            (. gen mark no-init-label)
            (. gen (throwException ex-type (str impl-pkg-name "/" prefix init-name " not defined")))
            (. gen mark end-label))
          (if (= pclasses super-pclasses)
            (do
              (. gen (loadThis))
              (. gen (loadArgs))
              (. gen (invokeConstructor super-type super-m)))
            (throw (new Exception ":init not specified, but ctor and super ctor args differ"))))

        (. gen (returnValue))
        (. gen (endMethod))
                                        ;factory
        (when factory
          (let [fm (new Method factory-name ctype ptypes)
                gen (new GeneratorAdapter (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_STATIC)) 
                         fm nil nil cv)]
            (. gen (visitCode))
            (. gen newInstance ctype)
            (. gen dup)
            (. gen (loadArgs))
            (. gen (invokeConstructor ctype m))            
            (. gen (returnValue))
            (. gen (endMethod))))))
    
                                        ;add methods matching supers', if no fn -> call super
    (let [mm (non-private-methods super)]
      (doseq [#^java.lang.reflect.Method meth (vals mm)]
             (emit-forwarding-method (.getName meth) (.getParameterTypes meth) (.getReturnType meth) false
                                     (fn [gen m]
                                       (. gen (loadThis))
                                        ;push args
                                       (. gen (loadArgs))
                                        ;call super
                                       (. gen (visitMethodInsn (. Opcodes INVOKESPECIAL) 
                                                               (. super-type (getInternalName))
                                                               (. m (getName))
                                                               (. m (getDescriptor)))))))
                                        ;add methods matching interfaces', if no fn -> throw
      (reduce (fn [mm meth]
                (if (contains? mm (method-sig meth))
                  mm
                  (do
                    (emit-forwarding-method (.getName meth) (.getParameterTypes meth) (.getReturnType meth) false
                                            emit-unsupported)
                    (assoc mm (method-sig meth) meth))))
              mm (mapcat #(.getMethods %) interfaces))
                                        ;extra methods
       (doseq [[mname pclasses rclass :as msig] methods]
         (emit-forwarding-method (str mname) pclasses rclass (:static ^msig)
                                 emit-unsupported)))

                                        ;main
    (when main
      (let [m (. Method getMethod "void main (String[])")
            gen (new GeneratorAdapter (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_STATIC)) 
                     m nil nil cv)
            no-main-label (. gen newLabel)
            end-label (. gen newLabel)]
        (. gen (visitCode))

        (emit-get-var gen main-name)
        (. gen dup)
        (. gen ifNull no-main-label)
        (. gen loadArgs)
        (. gen (invokeStatic rt-type (. Method (getMethod "clojure.lang.ISeq seq(Object)"))))
        (. gen (invokeInterface ifn-type (new Method "applyTo" obj-type 
                                              (into-array [iseq-type]))))
        (. gen pop)
        (. gen goTo end-label)
                                        ;no main found
        (. gen mark no-main-label)
        (. gen (throwException ex-type (str impl-pkg-name "/" prefix main-name " not defined")))
        (. gen mark end-label)
        (. gen (returnValue))
        (. gen (endMethod))))
                                        ;field exposers
    (doseq [[f {getter :get setter :set}] exposes]
      (let [fld (.getDeclaredField super (str f))
            ftype (totype (.getType fld))]
        (when getter
          (let [m (new Method (str getter) ftype (to-types []))
                gen (new GeneratorAdapter (. Opcodes ACC_PUBLIC) m nil nil cv)]
            (. gen (visitCode))
            (. gen loadThis)
            (. gen getField ctype (str f) ftype)
            (. gen (returnValue))
            (. gen (endMethod))))
        (when setter
          (let [m (new Method (str setter) (. Type VOID_TYPE) (into-array [ftype]))
                gen (new GeneratorAdapter (. Opcodes ACC_PUBLIC) m nil nil cv)]
            (. gen (visitCode))
            (. gen loadThis)
            (. gen loadArgs)
            (. gen putField ctype (str f) ftype)
            (. gen (returnValue))
            (. gen (endMethod))))))
                                        ;finish class def
    (. cv (visitEnd))
    [cname (. cv (toByteArray))]))

(defmacro gen-class 
  "When compiling, generates compiled bytecode for a class with the
  given package-qualified :name (which, as all names in these
  parameters, can be a string or symbol), and writes the .class file
  to the *compile-path* directory.  When not compiling, does
  nothing. The gen-class construct contains no implementation, as the
  implementation will be dynamically sought by the generated class in
  functions in an implementing Clojure namespace. Given a generated
  class org.mydomain.MyClass with a method named mymethod, gen-class
  will generate an implementation that looks for a function named by 
  (str prefix mymethod) (default prefix: \"-\") in a
  Clojure namespace specified by :impl-ns
  (defaults to the current namespace). All inherited methods,
  generated methods, and init and main functions (see :methods, :init,
  and :main below) will be found similarly prefixed. By default, the
  static initializer for the generated class will attempt to load the
  Clojure support code for the class as a resource from the classpath,
  e.g. in the example case, org/mydomain/MyClass__init.class. This
  behavior can be controlled by :load-impl-ns

  Note that methods with a maximum of 18 parameters are supported.

  In all subsequent sections taking types, the primitive types can be
  referred to by their Java names (int, float etc), and classes in the
  java.lang package can be used without a package qualifier. All other
  classes must be fully qualified.

  Options should be a set of key/value pairs, all except for :name are optional:

  :name aname

  The package-qualified name of the class to be generated

  :extends aclass

  Specifies the superclass, the non-private methods of which will be
  overridden by the class. If not provided, defaults to Object.

  :implements [interface ...]

  One or more interfaces, the methods of which will be implemented by the class.

  :init name

  If supplied, names a function that will be called with the arguments
  to the constructor. Must return [ [superclass-constructor-args] state] 
  If not supplied, the constructor args are passed directly to
  the superclass constructor and the state will be nil

  :constructors {[param-types] [super-param-types], ...}

  By default, constructors are created for the generated class which
  match the signature(s) of the constructors for the superclass. This
  parameter may be used to explicitly specify constructors, each entry
  providing a mapping from a constructor signature to a superclass
  constructor signature. When you supply this, you must supply an :init
  specifier. 

  :methods [ [name [param-types] return-type], ...]

  The generated class automatically defines all of the non-private
  methods of its superclasses/interfaces. This parameter can be used
  to specify the signatures of additional methods of the generated
  class. Static methods can be specified with #^{:static true} in the
  signature's metadata. Do not repeat superclass/interface signatures
  here.

  :main boolean

  If supplied and true, a static public main function will be generated. It will
  pass each string of the String[] argument as a separate argument to
  a function called (str prefix main).

  :factory name

  If supplied, a (set of) public static factory function(s) will be
  created with the given name, and the same signature(s) as the
  constructor(s).
  
  :state name

  If supplied, a public final instance field with the given name will be
  created. You must supply an :init function in order to provide a
  value for the state. Note that, though final, the state can be a ref
  or agent, supporting the creation of Java objects with transactional
  or asynchronous mutation semantics.

  :exposes {protected-field-name {:get name :set name}, ...}

  Since the implementations of the methods of the generated class
  occur in Clojure functions, they have no access to the inherited
  protected fields of the superclass. This parameter can be used to
  generate public getter/setter methods exposing the protected field(s)
  for use in the implementation.

  :prefix string

  Default: \"-\" Methods called e.g. Foo will be looked up in vars called
  prefixFoo in the implementing ns.

  :impl-ns name

  Default: the name of the current ns. Implementations of methods will be looked up in this namespace.

  :load-impl-ns boolean

  Default: true. Causes the static initializer for the generated class
  to reference the load code for the implementing namespace. Should be
  true when implementing-ns is the default, false if you intend to
  load the code via some other method."
  
  [& options]
    (when *compile-files*
      (let [options-map (apply hash-map options)
            [cname bytecode] (generate-class options-map)]
        (clojure.lang.Compiler/writeClassFile cname bytecode))))

(comment

(defn gen-and-load-class 
  "Generates and immediately loads the bytecode for the specified
  class. Note that a class generated this way can be loaded only once
  - the JVM supports only one class with a given name per
  classloader. Subsequent to generation you can import it into any
  desired namespaces just like any other class. See gen-class for a
  description of the options."

  [& options]
  (let [options-map (apply hash-map options)
        [cname bytecode] (generate-class options-map)]
    (.. clojure.lang.RT ROOT_CLASSLOADER (defineClass cname bytecode))))

)

(comment
;usage
(gen-class 
 package-qualified-name
  ;all below are optional
 :extends aclass
 :implements [interface ...]
 :constructors {[param-types] [super-param-types], }
 :methods [[name [param-types] return-type], ]
 :main boolean
 :factory name
 :state name
 :init name
 :exposes {protected-field {:get name :set name}, })

(gen-and-load-class 'net.n01se.TestObj
  :extends javax.swing.DefaultCellEditor
  :constructors {[Integer] [javax.swing.JCheckBox]}
  :factory 'makeone
  :methods [['mymax [Integer] Integer]]
  :init 'init
  :main true
  :state 'myint
  :exposes '{clickCountToStart {:get get-c-count :set set-c-count}})

;-------------------------------
(clojure/in-ns 'net.n01se)
(clojure/refer 'clojure)

(defn TestObj-init [myint]
  [[(javax.swing.JCheckBox.)] myint])

(defn TestObj-mymax [this i]
  (max i (.myint this)))

(defn TestObj-getCellEditorValue [this]
  (prn :getCellEditorValue)
  nil)

(defn TestObj-main [istr]
  (prn :main istr))

;-------------------------------
(in-ns 'user)
(prn (.mymax (net.n01se.TestObj. 5) 9))
(.getCellEditorValue (net.n01se.TestObj. 6))
(prn (.myint (net.n01se.TestObj. 7)))
(prn (. net.n01se.TestObj makeone 8))
(prn (net.n01se.TestObj/makeone 9))
(. net.n01se.TestObj main (into-array ["howdy"]))
(prn (net.n01se/TestObj-getCellEditorValue (net.n01se.TestObj. 10)))
(prn (.get-c-count (net.n01se.TestObj. 11)))

; the following would fail because :exposes only generates class
; methods, not namespace functions
;(prn (net.n01se.TestObj/get-c-count (net.n01se.TestObj. 12)))
)
