package com.jadxmp.ir.type

import com.jadxmp.ir.type.IrType.Companion.array
import com.jadxmp.ir.type.IrType.Companion.generic
import com.jadxmp.ir.type.IrType.Companion.objectType
import com.jadxmp.ir.type.IrType.Companion.typeVariable
import com.jadxmp.ir.type.IrType.Companion.unknown
import com.jadxmp.ir.type.IrType.Companion.wildcard
import com.jadxmp.ir.type.TypeRelation.CONFLICT
import com.jadxmp.ir.type.TypeRelation.CONFLICT_BY_GENERIC
import com.jadxmp.ir.type.TypeRelation.EQUAL
import com.jadxmp.ir.type.TypeRelation.NARROWER
import com.jadxmp.ir.type.TypeRelation.NARROWER_BY_GENERIC
import com.jadxmp.ir.type.TypeRelation.UNRELATED
import com.jadxmp.ir.type.TypeRelation.WIDER
import com.jadxmp.ir.type.TypeRelation.WIDER_BY_GENERIC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lattice spec, locked. Two layers:
 *  1. Per-case assertions against an independent expected value ([rel] checks both directions, so it
 *     also enforces antisymmetry).
 *  2. Property tests over a broad sample set that enforce the algebraic laws the meet-semilattice must
 *     satisfy — transitivity of the order and associativity of [IrType.merge] — which are exactly the
 *     laws whose earlier violation produced order-dependent type inference.
 */
class IrTypeTest {

    /** Assert `a compareWith b == expected` AND the inverted direction, enforcing antisymmetry. */
    private fun rel(a: IrType, b: IrType, expected: TypeRelation) {
        assertEquals(expected, a.compareWith(b), "$a vs $b")
        assertEquals(expected.invert(), b.compareWith(a), "$b vs $a (inverted)")
    }

    // ---- equality & identity ----

    @Test
    fun equalityIsStructural() {
        assertEquals(IrType.INT, IrType.primitive(TypeKind.INT))
        assertEquals(objectType("com.Foo"), objectType("com.Foo"))
        assertEquals(array(IrType.INT), array(IrType.INT))
        assertEquals(unknown(TypeKind.INT, TypeKind.FLOAT), unknown(TypeKind.FLOAT, TypeKind.INT))
        assertNotEquals(IrType.INT, IrType.LONG)
        assertNotEquals(objectType("com.Foo"), objectType("com.Bar"))
    }

    @Test
    fun internedWellKnownObjects() {
        assertEquals(IrType.STRING, objectType("java.lang.String"))
        assertEquals(IrType.OBJECT, objectType("java.lang.Object"))
    }

    @Test
    fun sameTypeIsEqualRelation() {
        rel(IrType.INT, IrType.INT, EQUAL)
        rel(IrType.STRING, IrType.STRING, EQUAL)
        rel(IrType.UNKNOWN, IrType.UNKNOWN, EQUAL)
    }

    // ---- primitives are a flat antichain (no widening order in the lattice) ----

    @Test
    fun primitivesAreAnAntichain() {
        // Every distinct pair of primitives conflicts — there is NO int⊂long widening here.
        val prims = listOf(
            IrType.BYTE, IrType.SHORT, IrType.CHAR, IrType.INT,
            IrType.LONG, IrType.FLOAT, IrType.DOUBLE, IrType.BOOLEAN, IrType.VOID,
        )
        for (a in prims) {
            for (b in prims) {
                assertEquals(if (a == b) EQUAL else CONFLICT, a.compareWith(b), "$a vs $b")
            }
        }
    }

    @Test
    fun shortAndCharAreIncomparable() {
        rel(IrType.SHORT, IrType.CHAR, CONFLICT)
        rel(IrType.BYTE, IrType.CHAR, CONFLICT)
    }

    @Test
    fun primitiveVersusReferenceConflicts() {
        rel(IrType.INT, IrType.OBJECT, CONFLICT)
        rel(IrType.INT, IrType.STRING, CONFLICT)
        rel(IrType.DOUBLE, array(IrType.INT), CONFLICT)
    }

    // ---- known vs unknown ----

    @Test
    fun knownIsNarrowerThanTop() {
        rel(IrType.INT, IrType.UNKNOWN, NARROWER)
        rel(IrType.STRING, IrType.UNKNOWN, NARROWER)
        rel(array(IrType.INT), IrType.UNKNOWN, NARROWER)
    }

    @Test
    fun knownVersusPartialSet() {
        rel(IrType.INT, IrType.NARROW_INTEGRAL, NARROWER) // int ∈ {int,short,byte,char}
        rel(IrType.LONG, IrType.WIDE, NARROWER) // long ∈ {long,double}
        rel(IrType.INT, IrType.WIDE, CONFLICT) // int ∉ {long,double}
        rel(IrType.LONG, IrType.NARROW_INTEGRAL, CONFLICT)
    }

    @Test
    fun rootObjectVersusReferencePartials() {
        // java.lang.Object admits arrays, so it is WIDER than a "non-array object" partial…
        rel(IrType.OBJECT, IrType.UNKNOWN_OBJECT_NO_ARRAY, WIDER)
        // …and same-kinds vs "object-or-array", where the resolved type is the narrower one.
        rel(IrType.OBJECT, IrType.UNKNOWN_OBJECT, NARROWER)
    }

    // ---- unknown set relations ----

    @Test
    fun partialSubsetIsNarrower() {
        rel(unknown(TypeKind.INT, TypeKind.FLOAT), IrType.NARROW_NUMBERS, NARROWER)
        rel(IrType.WIDE, IrType.UNKNOWN, NARROWER)
        rel(IrType.UNKNOWN_OBJECT_NO_ARRAY, IrType.UNKNOWN_OBJECT, NARROWER)
    }

    @Test
    fun partialDisjointConflicts() {
        rel(IrType.WIDE, IrType.NARROW_INTEGRAL, CONFLICT)
        rel(unknown(TypeKind.OBJECT), unknown(TypeKind.INT), CONFLICT)
    }

    @Test
    fun partialOverlapButNoSubsetIsUnrelated() {
        rel(unknown(TypeKind.INT, TypeKind.FLOAT), unknown(TypeKind.INT, TypeKind.BOOLEAN), UNRELATED)
    }

    // ---- objects ----

    @Test
    fun rootObjectIsWiderThanAnyClass() {
        rel(IrType.OBJECT, IrType.STRING, WIDER)
        rel(objectType("com.Foo"), IrType.OBJECT, NARROWER)
    }

    @Test
    fun distinctNamedClassesAreUnrelatedWithoutHierarchy() {
        rel(objectType("com.Foo"), objectType("com.Bar"), UNRELATED)
    }

    // ---- generics (invariant) ----

    @Test
    fun rawVersusParameterizedSameErasure() {
        val rawList = objectType("java.util.List")
        val listOfString = generic("java.util.List", IrType.STRING)
        rel(listOfString, rawList, NARROWER_BY_GENERIC)
        rel(rawList, listOfString, WIDER_BY_GENERIC)
    }

    @Test
    fun uniformlyNarrowerArgumentsPropagate() {
        rel(
            generic("java.util.List", IrType.STRING),
            generic("java.util.List", IrType.OBJECT),
            NARROWER,
        )
        assertEquals(
            EQUAL,
            generic("java.util.List", IrType.STRING).compareWith(generic("java.util.List", IrType.STRING)),
        )
    }

    @Test
    fun mixedVarianceArgumentsAreUnrelatedWithElementwiseMeet() {
        // Map<String,Object> vs Map<Object,String>: arg0 narrower, arg1 wider ⇒ not ordered, but the
        // meet exists elementwise = Map<String,String>. (This is the associativity witness.)
        val so = generic("java.util.Map", IrType.STRING, IrType.OBJECT)
        val os = generic("java.util.Map", IrType.OBJECT, IrType.STRING)
        rel(so, os, UNRELATED)
        assertEquals(generic("java.util.Map", IrType.STRING, IrType.STRING), so.merge(os))
    }

    @Test
    fun conflictingArgumentConflictsByGeneric() {
        rel(
            generic("java.util.List", objectType("com.Foo")),
            generic("java.util.List", objectType("com.Bar")),
            CONFLICT_BY_GENERIC, // Foo vs Bar have no meet at the argument ⇒ whole thing conflicts
        )
        assertNull(
            generic("java.util.List", objectType("com.Foo"))
                .merge(generic("java.util.List", objectType("com.Bar"))),
        )
    }

    @Test
    fun differentGenericArityConflictsByGeneric() {
        rel(
            generic("java.util.Map", IrType.STRING),
            generic("java.util.Map", IrType.STRING, IrType.OBJECT),
            CONFLICT_BY_GENERIC,
        )
    }

    // ---- type variables ----

    @Test
    fun typeVariableRelations() {
        rel(typeVariable("T"), typeVariable("T"), EQUAL)
        rel(typeVariable("T"), typeVariable("U"), CONFLICT)
        rel(typeVariable("T", listOf(IrType.STRING)), typeVariable("T"), NARROWER)
        rel(typeVariable("T"), IrType.OBJECT, NARROWER)
    }

    // ---- wildcards ----

    @Test
    fun wildcardRelations() {
        rel(wildcard(), wildcard(WildcardBound.EXTENDS, IrType.STRING), WIDER)
        rel(
            wildcard(WildcardBound.EXTENDS, IrType.STRING),
            wildcard(WildcardBound.SUPER, IrType.STRING),
            CONFLICT,
        )
    }

    // ---- arrays ----

    @Test
    fun referenceArraysAreCovariant() {
        rel(array(IrType.STRING), array(IrType.OBJECT), NARROWER)
        rel(array(IrType.INT), array(IrType.INT), EQUAL)
    }

    @Test
    fun primitiveArraysAreInvariant() {
        rel(array(IrType.INT), array(IrType.LONG), CONFLICT)
        rel(array(IrType.INT), array(IrType.OBJECT), CONFLICT) // int[] not-a Object[]
        // but a primitive array still narrows an array-of-unknown
        rel(array(IrType.INT), array(IrType.UNKNOWN), NARROWER)
    }

    @Test
    fun arrayVersusObjectRoot() {
        rel(array(IrType.INT), IrType.OBJECT, NARROWER) // any array is-a Object
    }

    @Test
    fun arrayVersusInterfaceIsUnrelated() {
        // arrays implement Cloneable/Serializable; without the class graph this is undecidable.
        rel(array(IrType.INT), objectType("java.io.Serializable"), UNRELATED)
        rel(array(IrType.INT), IrType.STRING, UNRELATED)
    }

    @Test
    fun arrayVersusPartialContainingArray() {
        rel(array(IrType.INT), IrType.UNKNOWN_OBJECT, NARROWER)
        rel(array(IrType.INT), IrType.UNKNOWN, NARROWER)
        rel(array(IrType.INT), IrType.UNKNOWN_OBJECT_NO_ARRAY, CONFLICT) // {object} does not admit array
    }

    @Test
    fun multiDimensionArrayShape() {
        val intArr2 = array(IrType.INT, 2)
        assertEquals(2, intArr2.arrayDimension)
        assertEquals(IrType.INT, intArr2.arrayRootElement)
        assertEquals(array(array(IrType.INT)), intArr2)
    }

    // ---- isCompatible ----

    @Test
    fun isCompatibleMirrorsNonConflict() {
        assertFalse(IrType.INT.isCompatible(IrType.LONG)) // antichain ⇒ incompatible
        assertTrue(IrType.INT.isCompatible(IrType.NARROW_INTEGRAL))
        assertTrue(IrType.INT.isCompatible(IrType.UNKNOWN))
        assertTrue(objectType("com.Foo").isCompatible(objectType("com.Bar"))) // unrelated ⇒ compatible
        assertFalse(IrType.INT.isCompatible(IrType.OBJECT))
        assertFalse(IrType.WIDE.isCompatible(IrType.NARROW_INTEGRAL))
        assertTrue(IrType.INT.isCompatible(IrType.INT))
    }

    // ---- merge (meet / GLB) ----

    @Test
    fun mergeIsIdempotent() {
        for (t in SAMPLES) assertEquals(t, t.merge(t), "idempotent: $t")
    }

    @Test
    fun mergePicksNarrower() {
        assertEquals(IrType.STRING, IrType.STRING.merge(IrType.OBJECT))
        assertEquals(IrType.STRING, IrType.OBJECT.merge(IrType.STRING))
        assertEquals(array(IrType.STRING), array(IrType.STRING).merge(array(IrType.OBJECT)))
    }

    @Test
    fun mergeKnownWithPartialResolvesToKnown() {
        assertEquals(IrType.INT, IrType.INT.merge(IrType.NARROW_INTEGRAL))
        assertEquals(IrType.INT, IrType.NARROW_INTEGRAL.merge(IrType.INT))
        assertEquals(IrType.STRING, IrType.STRING.merge(IrType.UNKNOWN))
    }

    @Test
    fun mergeOfPartialsIsSetIntersection() {
        assertEquals(IrType.NARROW_INTEGRAL, IrType.NARROW_NUMBERS.merge(IrType.NARROW_INTEGRAL))
        assertEquals(
            unknown(TypeKind.INT),
            unknown(TypeKind.INT, TypeKind.FLOAT).merge(unknown(TypeKind.INT, TypeKind.BOOLEAN)),
        )
        assertEquals(IrType.WIDE, IrType.UNKNOWN.merge(IrType.WIDE))
    }

    @Test
    fun mergeOfArraysIsElementwise() {
        assertEquals(array(IrType.STRING), array(IrType.STRING).merge(array(IrType.OBJECT)))
        assertEquals(array(IrType.INT), array(IrType.UNKNOWN).merge(array(IrType.INT)))
    }

    @Test
    fun mergeConflictIsNull() {
        assertNull(IrType.INT.merge(IrType.LONG)) // antichain
        assertNull(IrType.INT.merge(IrType.OBJECT))
        assertNull(IrType.WIDE.merge(IrType.NARROW_INTEGRAL))
        assertNull(array(IrType.INT).merge(array(IrType.LONG)))
    }

    @Test
    fun mergeUnrelatedNamedClassesIsNull() {
        assertNull(objectType("com.Foo").merge(objectType("com.Bar")))
    }

    // ---- predicates ----

    @Test
    fun typeKnownPredicate() {
        assertTrue(IrType.INT.isTypeKnown)
        assertTrue(array(IrType.INT).isTypeKnown)
        assertFalse(IrType.UNKNOWN.isTypeKnown)
        assertFalse(IrType.UNKNOWN_ARRAY.isTypeKnown)
        assertFalse(IrType.WIDE.isTypeKnown)
    }

    // ---- algebraic laws (property tests over the broad sample set) ----

    @Test
    fun antisymmetryHoldsEverywhere() {
        for (a in SAMPLES) {
            for (b in SAMPLES) {
                assertEquals(a.compareWith(b), b.compareWith(a).invert(), "antisymmetry $a vs $b")
            }
        }
    }

    @Test
    fun narrowerOrEqualOrderIsTransitive() {
        for (a in SAMPLES) {
            for (b in SAMPLES) {
                if (!a.compareWith(b).isNarrowerOrEqual) continue
                for (c in SAMPLES) {
                    if (!b.compareWith(c).isNarrowerOrEqual) continue
                    assertTrue(
                        a.compareWith(c).isNarrowerOrEqual,
                        "transitivity broken: $a <= $b <= $c but $a vs $c = ${a.compareWith(c)}",
                    )
                }
            }
        }
    }

    @Test
    fun mergeIsCommutative() {
        for (a in SAMPLES) {
            for (b in SAMPLES) {
                assertEquals(a.merge(b), b.merge(a), "merge commutativity $a,$b")
            }
        }
    }

    @Test
    fun mergeIsAssociative() {
        for (a in SAMPLES) {
            for (b in SAMPLES) {
                for (c in SAMPLES) {
                    val left = meet(meet(a, b), c)
                    val right = meet(a, meet(b, c))
                    assertEquals(left, right, "merge associativity ($a,$b,$c): $left vs $right")
                }
            }
        }
    }

    @Test
    fun mergeAgreesWithCompareWith() {
        for (a in SAMPLES) {
            for (b in SAMPLES) {
                val r = a.compareWith(b)
                val m = a.merge(b)
                when {
                    r.isEqual || r.isNarrower -> assertEquals(a, m, "meet($a,$b) should be $a for $r")
                    r.isWider -> assertEquals(b, m, "meet($a,$b) should be $b for $r")
                    r.isConflict -> assertNull(m, "meet($a,$b) should be null for $r")
                    // UNRELATED: merge may still compute a set intersection (unknown/array elements),
                    // so no strict expectation here.
                }
            }
        }
    }

    private companion object {
        /** null-absorbing meet, so associativity treats CONFLICT/UNRELATED (null) as bottom. */
        fun meet(a: IrType?, b: IrType?): IrType? = if (a == null || b == null) null else a.merge(b)

        /**
         * A broad cross-section: primitives, named classes, generics (mixed variance), wildcards,
         * type variables, reference/primitive/unknown-element arrays, and partial sets.
         */
        val SAMPLES: List<IrType> = listOf(
            IrType.INT, IrType.LONG, IrType.BOOLEAN, IrType.DOUBLE, IrType.VOID,
            IrType.OBJECT, IrType.STRING, objectType("com.Foo"), objectType("com.Bar"),
            generic("java.util.List", IrType.STRING),
            generic("java.util.List", IrType.OBJECT),
            generic("java.util.Map", IrType.STRING, IrType.OBJECT),
            generic("java.util.Map", IrType.OBJECT, IrType.STRING),
            generic("java.util.Map", IrType.STRING, IrType.STRING),
            generic("java.util.List", generic("java.util.Map", IrType.STRING, IrType.OBJECT)),
            typeVariable("T"), typeVariable("U"), typeVariable("T", listOf(IrType.STRING)),
            wildcard(), wildcard(WildcardBound.EXTENDS, IrType.STRING), wildcard(WildcardBound.SUPER, IrType.STRING),
            array(IrType.INT), array(IrType.LONG), array(IrType.STRING), array(IrType.OBJECT), array(IrType.UNKNOWN),
            IrType.UNKNOWN, IrType.WIDE, IrType.NARROW_INTEGRAL, IrType.NARROW_NUMBERS,
            IrType.UNKNOWN_OBJECT, IrType.UNKNOWN_OBJECT_NO_ARRAY,
            unknown(TypeKind.INT, TypeKind.FLOAT), unknown(TypeKind.INT, TypeKind.BOOLEAN),
        )
    }
}
