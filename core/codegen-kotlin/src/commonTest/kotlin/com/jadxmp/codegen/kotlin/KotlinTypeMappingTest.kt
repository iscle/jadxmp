package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * Kotlin leaf-emission accuracy: raw JDK generic types must project to `<*>` (Kotlin rejects a raw
 * `List`/`Map`), reference-array allocation must reconcile the nullable element `arrayOfNulls` yields
 * with the non-null declared array type, and a `null`-initialized local must be declared nullable.
 *
 * These mirror recurring kotlinc-recompile failures on the smali corpus (`One type argument expected`,
 * `Initializer type mismatch: expected 'Array<String>', actual 'Array<String?>'`, `Null cannot be a
 * value of a non-null type`).
 */
class KotlinTypeMappingTest {

    /** A raw `java.util.List` in a type position needs the star projection `List<*>`. */
    @Test
    fun rawListRendersStarProjection() {
        val cls = irClass("a.C")
        cls.method("m", argTypes = listOf(IrType.objectType("java.util.List"))) {
            this[CodegenKeys.PARAM_NAMES] = listOf("list")
            body(ret())
        }
        assertThatCode(generate(cls)).containsOne("list: List<*>")
    }

    /** A raw `java.util.Map` needs two star projections `Map<*, *>`. */
    @Test
    fun rawMapRendersTwoStarProjections() {
        val cls = irClass("a.C")
        cls.method("m", argTypes = listOf(IrType.objectType("java.util.Map"))) {
            this[CodegenKeys.PARAM_NAMES] = listOf("map")
            body(ret())
        }
        assertThatCode(generate(cls)).containsOne("map: Map<*, *>")
    }

    /** A raw `java.lang.Class` return type needs `Class<*>`. */
    @Test
    fun rawClassRendersStarProjection() {
        val cls = irClass("a.C")
        cls.method("m", returnType = IrType.CLASS) {
            body(ret())
        }
        assertThatCode(generate(cls)).containsOne(": Class<*>")
    }

    /** An already-parameterized generic is left untouched (no double projection). */
    @Test
    fun parameterizedListKeepsItsArgument() {
        val cls = irClass("a.C")
        cls.method("m", argTypes = listOf(IrType.generic("java.util.List", IrType.STRING))) {
            this[CodegenKeys.PARAM_NAMES] = listOf("list")
            body(ret())
        }
        assertThatCode(generate(cls))
            .containsOne("list: List<String>")
            .doesNotContain("List<*>")
    }

    /**
     * A raw generic used as a bare-name receiver (a static call target) must stay a bare name — a
     * `<*>` projection is illegal there (`Class<*>.x(..)` does not compile).
     */
    @Test
    fun rawGenericStaticReceiverStaysBareName() {
        val cls = irClass("a.C")
        cls.method("m") {
            body(
                staticInvoke(IrType.CLASS, "x", IrType.VOID, listOf(IrType.INT), listOf(intLit(1))),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("Class.x(1)")
            .doesNotContain("Class<*>")
    }

    /**
     * `new String[3]` becomes `arrayOfNulls<String>(3)` (a `Array<String?>`) cast to the non-null
     * declared element type, so it assigns to a `Array<String>` variable without a type mismatch.
     */
    @Test
    fun referenceArrayNewCastsToNonNullElement() {
        val cls = irClass("a.C")
        val arr = Local(1, IrType.array(IrType.STRING))
        cls.method("m") {
            body(
                assign(arr.ref(), newArray(IrType.array(IrType.STRING), intLit(3))),
                ret(),
            )
        }
        assertThatCode(generate(cls)).containsOne("arrayOfNulls<String>(3) as Array<String>")
    }

    /** A primitive-element `new int[3]` uses the dedicated `IntArray(3)` and needs no cast. */
    @Test
    fun primitiveArrayNewHasNoCast() {
        val cls = irClass("a.C")
        val arr = Local(1, IrType.array(IrType.INT))
        cls.method("m") {
            body(
                assign(arr.ref(), newArray(IrType.array(IrType.INT), intLit(3))),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("IntArray(3)")
            .doesNotContain("as Array")
    }

    /** A local initialized to the `null` literal is declared with a nullable type. */
    @Test
    fun nullLiteralLocalIsDeclaredNullable() {
        val cls = irClass("a.C")
        val o = Local(1, IrType.OBJECT)
        cls.method("m") {
            body(
                assign(o.ref(), Instruction(IrOpcode.CONST, args = listOf(lit(0L, IrType.OBJECT)))),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne(": Any? = null")
            .doesNotContain(": Any = null")
    }

    /** A local with a non-null initializer keeps its non-null declared type. */
    @Test
    fun nonNullLocalStaysNonNull() {
        val cls = irClass("a.C")
        val p = Local(1, IrType.objectType("a.Point"))
        cls.method("m") {
            body(
                assign(p.ref(), constructor(IrType.objectType("a.Point"), emptyList(), emptyList())),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne(": Point = Point()")
            .doesNotContain("Point? =")
    }
}
