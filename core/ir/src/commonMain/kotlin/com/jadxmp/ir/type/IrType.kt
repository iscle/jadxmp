package com.jadxmp.ir.type

/**
 * The immutable type lattice over IR values.  **jadx: ArgType**
 *
 * DEX registers are untyped; Java source is typed. Reconstructing the second from the first is type
 * inference, and it runs over this lattice. Every value in the IR carries an [IrType] — either a
 * fully resolved Java type or a *partial* type that still stands for a set of possibilities.
 *
 * ## The lattice
 *
 * Ordered by **narrowness** (specificity):
 *
 * ```
 *                         Unknown(ALL)          "??"  — top: could be anything
 *                        /     |      \
 *              Unknown{L,[}  Wide{J,D}  NarrowNumbers{Z,I,F,S,B,C} ...   partial types
 *                 /    \                    |        \
 *            Object   ArrayType         Primitive(INT)  Primitive(FLOAT) ...  resolved types
 *               |
 *          Object("String")  ...  named classes (subtype edges need the class graph)
 *                        \      /
 *                        CONFLICT              — bottom: no value inhabits both
 * ```
 *
 * - **Concrete types** ([Primitive], [Object], [ArrayType], [TypeVariable], [Wildcard]) are resolved:
 *   [isTypeKnown] is true. An [ArrayType] is known only if its element is.
 * - **Partial types** ([Unknown]) carry a non-empty set of [TypeKind]s the value could still be,
 *   ordered by subset (`⊆`). The all-kinds set is the top; smaller sets are narrower. This is the
 *   ONLY place bytecode ambiguity is modelled (`UNKNOWN`, `NARROW`, `WIDE`, `NARROW_INTEGRAL`, …).
 *
 * ## Meet-semilattice discipline (why the ordering is what it is)
 *
 * The order must be a genuine partial order and every pair must have a well-defined meet, or
 * [merge] would be non-associative and type inference (which meets constraints in data-flow order)
 * would produce order-dependent results and spurious `inconsistent` markers. Two rules keep it sound:
 *
 * - **Primitives are a flat antichain.** Two unequal primitives (and any primitive vs reference)
 *   CONFLICT — there is no numeric-widening order in the lattice. `int` is *not* "narrower than"
 *   `long` here. A register that could be int/short/byte/char is expressed as an [Unknown] set
 *   (`NARROW_INTEGRAL`), and it narrows by **set intersection**, never by widening. Assignment
 *   widening (int is assignable to long) is an analysis/codegen judgement against real assignment
 *   rules — deliberately NOT the lattice meet.
 * - **References defer to the class graph.** Two distinct named classes are [TypeRelation.UNRELATED]
 *   (not conflict): their subtype edge needs a hierarchy this module does not hold. `java.lang.Object`
 *   is the reference top. Arrays are covariant for reference elements, invariant for primitive
 *   elements (`int[]` vs `long[]` conflict); array-vs-interface is UNRELATED (arrays implement
 *   Cloneable/Serializable); generics are invariant (mixed-variance arguments conflict).
 *
 * ## The three relations (the reused core — see [IrTypeTest] for the exhaustive spec)
 *
 * - [compareWith] `(other) -> TypeRelation`: where `this` sits vs `other` — EQUAL / NARROWER / WIDER
 *   / CONFLICT / UNRELATED. Antisymmetric (`a.compareWith(b) == b.compareWith(a).invert()`) and the
 *   NARROWER/EQUAL order is transitive.
 * - [isCompatible] `(other) -> Boolean`: could a single value satisfy both? True unless [compareWith]
 *   is a conflict. Note UNRELATED pairs are compatible even though [merge] returns null for them.
 * - [merge] `(other) -> IrType?`: the lattice **meet** (greatest lower bound). Commutative,
 *   idempotent, and **associative**. Returns `null` when there is no computable meet: a genuine
 *   CONFLICT, or an UNRELATED pair whose meet needs the class graph.
 *
 * All instances are immutable and safe to share/cache.
 */
sealed class IrType {

    // ---- kind queries -------------------------------------------------------

    /** True for resolved types; false for [Unknown] (and arrays with an unknown element). */
    val isTypeKnown: Boolean
        get() = when (this) {
            is Unknown -> false
            is ArrayType -> element.isTypeKnown
            else -> true
        }

    val isPrimitive: Boolean get() = this is Primitive
    val isObject: Boolean get() = this is Object
    val isArray: Boolean get() = this is ArrayType
    val isTypeVariable: Boolean get() = this is TypeVariable
    val isWildcard: Boolean get() = this is Wildcard

    /** Carries generic information (parameterized object, type variable, or wildcard). */
    val isGeneric: Boolean
        get() = this is TypeVariable || this is Wildcard || (this is Object && generics.isNotEmpty())

    /** Membership test used by narrowing: does this type admit values of [kind]? */
    fun contains(kind: TypeKind): Boolean = when (this) {
        is Unknown -> kind in possible
        is Primitive -> this.kind == kind
        is ArrayType -> kind == TypeKind.ARRAY
        is Object, is TypeVariable, is Wildcard -> kind == TypeKind.OBJECT
    }

    /** For an array, the element type; otherwise null. */
    val arrayElement: IrType? get() = (this as? ArrayType)?.element

    /** Number of array dimensions (0 for non-arrays). */
    val arrayDimension: Int get() = if (this is ArrayType) 1 + element.arrayDimension else 0

    /** Innermost element of a (possibly multi-dimensional) array, or the type itself. */
    val arrayRootElement: IrType get() = if (this is ArrayType) element.arrayRootElement else this

    /**
     * The set of [TypeKind]s a *known* type can inhabit — its coarse "kind signature", used to place
     * it against a partial [Unknown] set. `java.lang.Object` is the reference top, so it carries both
     * `OBJECT` and `ARRAY` (an `Object` value may be an array). Only valid when [isTypeKnown].
     */
    private fun kindSet(): Set<TypeKind> = when (this) {
        is Primitive -> setOf(kind)
        is Object -> if (isRootObject) ROOT_OBJECT_KINDS else OBJECT_KINDS
        is ArrayType -> ARRAY_KINDS
        is TypeVariable, is Wildcard -> OBJECT_KINDS
        is Unknown -> error("kindSet() on Unknown")
    }

    // ---- relation 1: compareWith -------------------------------------------

    /** See the class doc. Result is from the point of view of `this`. */
    fun compareWith(other: IrType): TypeRelation {
        if (this == other) return TypeRelation.EQUAL

        // A partial type on either side is compared purely by kind sets (subset ordering). kindSet
        // covers arrays and objects too, so this branch handles every "known vs Unknown" pairing.
        if (this is Unknown || other is Unknown) {
            if (this is Unknown && other is Unknown) return compareSets(possible, other.possible)
            return if (this is Unknown) compareKnownVsUnknown(other, this).invert()
            else compareKnownVsUnknown(this, other as Unknown)
        }

        // Both sides are known here.
        if (this is ArrayType && other is ArrayType) return compareArrayElements(element, other.element)
        if (this is ArrayType) return compareArrayToNonArray(this, other)
        if (other is ArrayType) return compareArrayToNonArray(other, this).invert()

        // Both known, non-array: Primitive / Object / TypeVariable / Wildcard.
        // Primitives are a flat antichain — unequal primitives (and any primitive vs reference)
        // never share a value, so they CONFLICT. Ambiguous integral registers are NOT modelled here;
        // they ride on Unknown partial sets (NARROW_INTEGRAL, …) and narrow via set intersection.
        if (this is Primitive || other is Primitive) return TypeRelation.CONFLICT
        return compareReferences(this, other)
    }

    /**
     * Element comparison for two array types. Reference-element arrays are covariant (`String[]` is-a
     * `Object[]`); primitive-element arrays are **invariant** (`int[]` and `long[]` are unrelated) —
     * but only once both elements are known primitives, so `int[]` can still narrow `??[]`.
     */
    private fun compareArrayElements(e1: IrType, e2: IrType): TypeRelation {
        if (e1.isTypeKnown && e2.isTypeKnown && (e1 is Primitive || e2 is Primitive)) {
            return if (e1 == e2) TypeRelation.EQUAL else TypeRelation.CONFLICT
        }
        return e1.compareWith(e2)
    }

    private fun compareArrayToNonArray(array: ArrayType, other: IrType): TypeRelation = when (other) {
        // Every array is-a java.lang.Object, so an array is narrower than the root object.
        is Object -> if (other.isRootObject) TypeRelation.NARROWER else TypeRelation.UNRELATED
        // Arrays implement Cloneable/Serializable, so array-vs-interface is undecidable here.
        is TypeVariable, is Wildcard -> TypeRelation.UNRELATED
        // Arrays are references; a primitive can never be an array.
        is Primitive -> TypeRelation.CONFLICT
        is ArrayType, is Unknown -> error("unreachable: handled in compareWith")
    }

    /** Pure subset ordering of two partial kind sets. */
    private fun compareSets(a: Set<TypeKind>, b: Set<TypeKind>): TypeRelation {
        if (a == b) return TypeRelation.EQUAL
        val inter = a intersect b
        return when {
            inter.isEmpty() -> TypeRelation.CONFLICT
            b.containsAll(a) -> TypeRelation.NARROWER // a ⊂ b : fewer options ⇒ more specific
            a.containsAll(b) -> TypeRelation.WIDER // b ⊂ a
            else -> TypeRelation.UNRELATED // overlap, neither contains the other
        }
    }

    private fun compareKnownVsUnknown(known: IrType, unknown: Unknown): TypeRelation {
        val ks = known.kindSet()
        val s = unknown.possible
        val inter = ks intersect s
        return when {
            inter.isEmpty() -> TypeRelation.CONFLICT
            // ks ⊆ s (incl. equal): the resolved type is more specific than the partial set.
            s.containsAll(ks) -> TypeRelation.NARROWER
            // s ⊂ ks: the known type is broader (only the root object, {OBJECT,ARRAY}).
            ks.containsAll(s) -> TypeRelation.WIDER
            else -> TypeRelation.UNRELATED // partial overlap
        }
    }

    private fun compareReferences(a: IrType, b: IrType): TypeRelation {
        if (a is TypeVariable || b is TypeVariable) {
            return if (a is TypeVariable) compareTypeVariable(a, b) else compareTypeVariable(b as TypeVariable, a).invert()
        }
        if (a is Wildcard || b is Wildcard) {
            if (a is Wildcard && b is Wildcard) return compareWildcards(a, b)
            // One wildcard, one Object. A wildcard is an opaque reference (kind {OBJECT}); to stay
            // consistent with that kind set it must order the same way a distinct object would —
            // narrower than the root, unrelated to any named class.
            val obj = (if (a is Wildcard) b else a) as Object
            if (!obj.isRootObject) return TypeRelation.UNRELATED
            return if (a is Wildcard) TypeRelation.NARROWER else TypeRelation.WIDER
        }
        a as Object
        b as Object
        if (a.className == b.className) return compareSameClassGenerics(a, b)
        if (a.isRootObject) return TypeRelation.WIDER
        if (b.isRootObject) return TypeRelation.NARROWER
        // Two distinct named classes: their subtype edge lives in the class graph, not here.
        return TypeRelation.UNRELATED
    }

    /**
     * Compare two parameterizations of the same class. Type arguments meet **elementwise** (like
     * array elements / unknown sets), which keeps a true meet-semilattice:
     * - uniformly narrower/wider arguments propagate to NARROWER / WIDER;
     * - **mixed** variance (some narrower, some wider) is UNRELATED — the two are not ordered, but a
     *   meet still exists elementwise (`Map<String,Object> ⊓ Map<Object,String> = Map<String,String>`),
     *   which [merge] computes;
     * - an argument pair with **no meet** (a real conflict) makes the whole thing CONFLICT_BY_GENERIC.
     */
    private fun compareSameClassGenerics(a: Object, b: Object): TypeRelation {
        val ag = a.generics
        val bg = b.generics
        if (ag.isEmpty() != bg.isEmpty()) {
            // raw List vs List<String>: same erasure, differ only by generic detail
            return if (ag.isEmpty()) TypeRelation.WIDER_BY_GENERIC else TypeRelation.NARROWER_BY_GENERIC
        }
        if (ag.size != bg.size) return TypeRelation.CONFLICT_BY_GENERIC
        var sawNarrow = false
        var sawWider = false
        for (i in ag.indices) {
            val r = ag[i].compareWith(bg[i])
            when {
                r.isEqual -> {}
                r.isNarrower -> sawNarrow = true
                r.isWider -> sawWider = true
                // Not ordered at this argument. It still has a meet unless it truly conflicts;
                // if it conflicts the whole parameterization has no meet.
                else -> if (ag[i].merge(bg[i]) == null) return TypeRelation.CONFLICT_BY_GENERIC else {
                    sawNarrow = true
                    sawWider = true
                }
            }
        }
        return when {
            sawNarrow && sawWider -> TypeRelation.UNRELATED // mixed variance: meet exists elementwise
            sawNarrow -> TypeRelation.NARROWER
            sawWider -> TypeRelation.WIDER
            else -> TypeRelation.EQUAL
        }
    }

    private fun compareTypeVariable(tv: TypeVariable, other: IrType): TypeRelation {
        if (other is TypeVariable) {
            // Distinct type parameters denote distinct (non-interchangeable) types.
            if (tv.name != other.name) return TypeRelation.CONFLICT
            val a = tv.extendTypes
            val b = other.extendTypes
            if (a == b) return TypeRelation.EQUAL
            if (a.isEmpty()) return TypeRelation.WIDER
            if (b.isEmpty()) return TypeRelation.NARROWER
            if (a.size == 1 && b.size == 1) return a[0].compareWith(b[0])
            return TypeRelation.CONFLICT
        }
        // T vs a concrete type: narrower than the root object; unrelated to any named class. A bound
        // (`T extends String`) does NOT create a ≤ edge to that class here — doing so would let a
        // bounded and unbounded T share a concrete lower bound and break meet consistency. Bound-aware
        // subtyping is deferred to the pipeline's class graph.
        if (other is Object) {
            return if (other.isRootObject) TypeRelation.NARROWER else TypeRelation.UNRELATED
        }
        return TypeRelation.UNRELATED
    }

    private fun compareWildcards(a: Wildcard, b: Wildcard): TypeRelation {
        if (a.bound == WildcardBound.UNBOUNDED) return TypeRelation.WIDER
        if (b.bound == WildcardBound.UNBOUNDED) return TypeRelation.NARROWER
        if (a.bound != b.bound) return TypeRelation.CONFLICT
        val at = a.boundType ?: return TypeRelation.UNRELATED
        val bt = b.boundType ?: return TypeRelation.UNRELATED
        return at.compareWith(bt)
    }

    // ---- relation 2: isCompatible ------------------------------------------

    /**
     * Could a single value satisfy both types? True unless [compareWith] is a conflict — so an
     * [TypeRelation.UNRELATED] pair (two distinct named classes) is *compatible* even though [merge]
     * returns null for it: the meet exists in principle but needs the class graph to name.
     * Reflexive and symmetric.
     */
    fun isCompatible(other: IrType): Boolean = !compareWith(other).isConflict

    // ---- relation 3: merge (meet / GLB) ------------------------------------

    /**
     * The lattice meet (narrowest type consistent with both), or `null` when none is computable.
     * `null` means either a genuine conflict OR an [TypeRelation.UNRELATED] pair whose meet needs the
     * class graph — callers that must tell these apart consult [compareWith]/[isCompatible].
     * Commutative and idempotent.
     */
    fun merge(other: IrType): IrType? {
        if (this == other) return this
        if (this is ArrayType && other is ArrayType) {
            val e1 = element
            val e2 = other.element
            // Known primitive-element arrays are invariant: unequal ⇒ no common array subtype.
            if (e1.isTypeKnown && e2.isTypeKnown && (e1 is Primitive || e2 is Primitive)) {
                return if (e1 == e2) this else null
            }
            val merged = e1.merge(e2) ?: return null
            return ArrayType(merged)
        }
        if (this is Unknown && other is Unknown) {
            return unknownFromSet(possible intersect other.possible)
        }
        // Two parameterizations of the same class meet elementwise (a real meet even under mixed
        // variance); null if any type-argument pair has no meet.
        if (this is Object && other is Object &&
            className == other.className &&
            generics.isNotEmpty() && other.generics.size == generics.size
        ) {
            val merged = ArrayList<IrType>(generics.size)
            for (i in generics.indices) {
                merged.add(generics[i].merge(other.generics[i]) ?: return null)
            }
            return Object(className, merged)
        }
        return when (compareWith(other)) {
            TypeRelation.EQUAL, TypeRelation.NARROWER, TypeRelation.NARROWER_BY_GENERIC -> this
            TypeRelation.WIDER, TypeRelation.WIDER_BY_GENERIC -> other
            // CONFLICT: no common lower bound. UNRELATED: undecidable without the class graph.
            else -> null
        }
    }

    // ---- concrete lattice elements -----------------------------------------

    /** A resolved primitive type. jadx: PrimitiveArg */
    @ConsistentCopyVisibility
    data class Primitive internal constructor(val kind: TypeKind) : IrType() {
        init {
            require(kind != TypeKind.OBJECT && kind != TypeKind.ARRAY) {
                "OBJECT/ARRAY are reference kinds, not primitives"
            }
        }

        override fun toString(): String = kind.name.lowercase()
    }

    /**
     * A resolved reference (class/interface) type, optionally parameterized with [generics].
     * jadx: ObjectType / GenericObject
     */
    @ConsistentCopyVisibility
    data class Object internal constructor(
        val className: String,
        val generics: List<IrType> = emptyList(),
    ) : IrType() {
        val isRootObject: Boolean get() = className == OBJECT_CLASS && generics.isEmpty()

        override fun toString(): String =
            if (generics.isEmpty()) className else "$className<${generics.joinToString(", ")}>"
    }

    /** A generic type variable `T` with its (possibly empty) upper bounds. jadx: GenericType */
    @ConsistentCopyVisibility
    data class TypeVariable internal constructor(
        val name: String,
        val extendTypes: List<IrType> = emptyList(),
    ) : IrType() {
        override fun toString(): String =
            if (extendTypes.isEmpty()) name else "$name extends ${extendTypes.joinToString(" & ")}"
    }

    /** A generic wildcard `?`, `? extends T`, `? super T`. jadx: WildcardType */
    @ConsistentCopyVisibility
    data class Wildcard internal constructor(
        val bound: WildcardBound,
        val boundType: IrType?,
    ) : IrType() {
        override fun toString(): String =
            if (bound == WildcardBound.UNBOUNDED) "?" else "${bound.prefix}$boundType"
    }

    /** An array type. Known iff [element] is known. jadx: ArrayArg */
    @ConsistentCopyVisibility
    data class ArrayType internal constructor(val element: IrType) : IrType() {
        override fun toString(): String = "$element[]"
    }

    /**
     * A *partial* type: the value is one of [possible] but which is not yet decided.
     * Invariant: [possible] is non-empty. jadx: UnknownArg
     */
    @ConsistentCopyVisibility
    data class Unknown internal constructor(val possible: Set<TypeKind>) : IrType() {
        init {
            require(possible.isNotEmpty()) { "Unknown type must admit at least one kind" }
        }

        override fun toString(): String =
            if (possible == TypeKind.ALL) "??" else "??${possible.map { it.descriptor }.joinToString("", "[", "]")}"
    }

    companion object {
        const val OBJECT_CLASS = "java.lang.Object"
        private const val STRING_CLASS = "java.lang.String"
        private const val CLASS_CLASS = "java.lang.Class"
        private const val ENUM_CLASS = "java.lang.Enum"
        private const val THROWABLE_CLASS = "java.lang.Throwable"

        // Resolved primitives.
        val BOOLEAN = Primitive(TypeKind.BOOLEAN)
        val CHAR = Primitive(TypeKind.CHAR)
        val BYTE = Primitive(TypeKind.BYTE)
        val SHORT = Primitive(TypeKind.SHORT)
        val INT = Primitive(TypeKind.INT)
        val FLOAT = Primitive(TypeKind.FLOAT)
        val LONG = Primitive(TypeKind.LONG)
        val DOUBLE = Primitive(TypeKind.DOUBLE)
        val VOID = Primitive(TypeKind.VOID)

        // Common resolved reference types.
        val OBJECT = Object(OBJECT_CLASS)
        val STRING = Object(STRING_CLASS)
        val CLASS = Object(CLASS_CLASS)
        val ENUM = Object(ENUM_CLASS)
        val THROWABLE = Object(THROWABLE_CLASS)
        val OBJECT_ARRAY = ArrayType(OBJECT)

        // Partial / unknown types.
        /** Top of the lattice: could be anything. */
        val UNKNOWN = Unknown(TypeKind.ALL)

        /** A reference of some sort (object or array), but not a primitive. */
        val UNKNOWN_OBJECT = Unknown(setOf(TypeKind.OBJECT, TypeKind.ARRAY))

        /** A non-array object reference. */
        val UNKNOWN_OBJECT_NO_ARRAY = Unknown(setOf(TypeKind.OBJECT))

        /** An array whose element type is not yet known. */
        val UNKNOWN_ARRAY = ArrayType(UNKNOWN)

        /** Any single-slot type (the DEX "narrow" category: 32-bit primitives or a reference). */
        val NARROW = Unknown(
            setOf(
                TypeKind.INT, TypeKind.FLOAT, TypeKind.BOOLEAN, TypeKind.SHORT,
                TypeKind.BYTE, TypeKind.CHAR, TypeKind.OBJECT, TypeKind.ARRAY,
            ),
        )

        /** Any single-slot number (32-bit numeric, possibly boolean). */
        val NARROW_NUMBERS = Unknown(
            setOf(TypeKind.BOOLEAN, TypeKind.INT, TypeKind.FLOAT, TypeKind.SHORT, TypeKind.BYTE, TypeKind.CHAR),
        )

        /** A 32-bit integral type. */
        val NARROW_INTEGRAL = Unknown(setOf(TypeKind.INT, TypeKind.SHORT, TypeKind.BYTE, TypeKind.CHAR))

        /** The DEX "wide" category: the two-slot primitives. */
        val WIDE = Unknown(setOf(TypeKind.LONG, TypeKind.DOUBLE))

        val INT_FLOAT = Unknown(setOf(TypeKind.INT, TypeKind.FLOAT))
        val INT_BOOLEAN = Unknown(setOf(TypeKind.INT, TypeKind.BOOLEAN))

        // ---- factories ----

        fun primitive(kind: TypeKind): IrType = when (kind) {
            TypeKind.BOOLEAN -> BOOLEAN
            TypeKind.CHAR -> CHAR
            TypeKind.BYTE -> BYTE
            TypeKind.SHORT -> SHORT
            TypeKind.INT -> INT
            TypeKind.FLOAT -> FLOAT
            TypeKind.LONG -> LONG
            TypeKind.DOUBLE -> DOUBLE
            TypeKind.VOID -> VOID
            TypeKind.OBJECT -> OBJECT
            TypeKind.ARRAY -> OBJECT_ARRAY
        }

        /** Build a resolved object type, interning the well-known names. */
        fun objectType(className: String): IrType = when (className) {
            OBJECT_CLASS -> OBJECT
            STRING_CLASS -> STRING
            CLASS_CLASS -> CLASS
            ENUM_CLASS -> ENUM
            THROWABLE_CLASS -> THROWABLE
            else -> Object(className)
        }

        /** A parameterized object type, e.g. `List<String>`. */
        fun generic(className: String, generics: List<IrType>): IrType = Object(className, generics)

        fun generic(className: String, vararg generics: IrType): IrType = Object(className, generics.toList())

        fun array(element: IrType): IrType = ArrayType(element)

        fun array(element: IrType, dimension: Int): IrType {
            require(dimension >= 1) { "array dimension must be >= 1" }
            var t = element
            repeat(dimension) { t = ArrayType(t) }
            return t
        }

        fun typeVariable(name: String, extendTypes: List<IrType> = emptyList()): IrType =
            TypeVariable(name, extendTypes)

        fun wildcard(): IrType = Wildcard(WildcardBound.UNBOUNDED, null)

        fun wildcard(bound: WildcardBound, boundType: IrType): IrType = Wildcard(bound, boundType)

        /** A partial type over the given kinds; a single all-kinds call yields [UNKNOWN]. */
        fun unknown(vararg kinds: TypeKind): IrType = unknownFromSet(kinds.toSet())
            ?: error("unknown() needs at least one kind")

        // Kind signatures for known types (see IrType.kindSet). The root object can hold arrays too.
        private val ROOT_OBJECT_KINDS = setOf(TypeKind.OBJECT, TypeKind.ARRAY)
        private val OBJECT_KINDS = setOf(TypeKind.OBJECT)
        private val ARRAY_KINDS = setOf(TypeKind.ARRAY)

        /** Build a partial type from a set, or `null` when the set is empty (a conflict). */
        internal fun unknownFromSet(kinds: Set<TypeKind>): IrType? = when {
            kinds.isEmpty() -> null
            kinds == TypeKind.ALL -> UNKNOWN
            else -> Unknown(kinds)
        }
    }
}
