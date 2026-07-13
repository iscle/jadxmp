package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * Data-class reconstruction in the Kotlin backend: the JVM shape of
 * `data class X(val a: A, var b: B)` (canonical ctor + `componentN` + `copy` + generated
 * value members) is detected conservatively and rendered back as a `data class`.
 *
 * Only the members a user CANNOT author on a data class are suppressed — the canonical
 * constructor, `componentN`, `copy`/`copy$default`. `equals`/`hashCode`/`toString` are KEPT in
 * the body because a data class may legally override them and signature alone can't tell a
 * default from a user body (dropping them would be silent code loss).
 */
class KotlinDataClassTest {

    /** Add a plain (non-static) instance field. */
    private fun IrClass.field(name: String, type: IrType, flags: Int) {
        fields.add(IrField(this, name, type, flags))
    }

    /** Give [cls] the full generated data-class member set for properties of types [propTypes]. */
    private fun IrClass.addCanonicalDataMembers(propTypes: List<IrType>) {
        method("<init>", argTypes = propTypes, accessFlags = Flags.PUBLIC) { body() }
        propTypes.forEachIndexed { i, t ->
            method("component${i + 1}", returnType = t, accessFlags = Flags.PUBLIC) { body() }
        }
        method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT), accessFlags = Flags.PUBLIC) {
            body(ret(intLit(0)))
        }
        method("hashCode", returnType = IrType.INT, accessFlags = Flags.PUBLIC) { body(ret(intLit(0))) }
        method("toString", returnType = IrType.STRING, accessFlags = Flags.PUBLIC) { body(ret(expr(constString("s")))) }
        method("copy", returnType = IrType.objectType(fullName), argTypes = propTypes, accessFlags = Flags.PUBLIC) {
            body()
        }
    }

    @Test
    fun fullShapeBecomesDataClassSuppressingOnlyGeneratedOnlyMembers() {
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL) // val (final)
        cls.field("b", IrType.INT, Flags.PRIVATE) // var (non-final)
        cls.addCanonicalDataMembers(listOf(IrType.STRING, IrType.INT))
        // A genuine user method that is NOT part of the canonical set must survive.
        cls.method("greet", returnType = IrType.STRING) { body(ret(expr(constString("hi")))) }

        assertThatCode(generate(cls))
            // Properties in the primary constructor, val/var per final-ness, correct types.
            .containsOne("data class Foo(val a: String, var b: Int) {")
            // Generated-only members (only the compiler can author these on a data class) are gone.
            .doesNotContain("component1")
            .doesNotContain("component2")
            .doesNotContain("fun copy")
            // The canonical constructor moved into the header, not a body `constructor(...)`.
            .doesNotContain("constructor")
            // equals/hashCode/toString are KEPT (a user may override them; lossless to keep).
            .containsOne("override fun equals(obj: Any?): Boolean {")
            .containsOne("override fun hashCode(): Int {")
            .containsOne("override fun toString(): String {")
            // The real user method is preserved.
            .containsOne("fun greet(): String {")
    }

    @Test
    fun defaultDataClassKeepsValueMembersInBody() {
        // (c) A genuine data class WITH copy + default value members → `data class`, with
        // equals/hashCode/toString kept in the body (lossless), componentN/copy suppressed.
        val cls = irClass("a.P", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.field("b", IrType.INT, Flags.PRIVATE)
        cls.addCanonicalDataMembers(listOf(IrType.STRING, IrType.INT))

        assertThatCode(generate(cls))
            .containsOne("data class P(val a: String, var b: Int) {")
            .containsOne("override fun equals(obj: Any?): Boolean {")
            .containsOne("override fun hashCode(): Int {")
            .containsOne("override fun toString(): String {")
            .doesNotContain("component1")
            .doesNotContain("component2")
            .doesNotContain("fun copy")
            .doesNotContain("constructor")
    }

    @Test
    fun dataClassWithCustomToStringOverrideKeepsCustomBody() {
        // (a) A real data class may override toString; the custom body must SURVIVE (not be
        // suppressed and silently regenerated as the default), while it stays a `data class`.
        val cls = irClass("a.Ov", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.method("<init>", argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC) { body() }
        cls.method("component1", returnType = IrType.STRING, accessFlags = Flags.PUBLIC) { body() }
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT)) { body(ret(intLit(0))) }
        cls.method("hashCode", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("toString", returnType = IrType.STRING) { body(ret(expr(constString("MY_CUSTOM_REPR")))) }
        cls.method("copy", returnType = IrType.objectType("a.Ov"), argTypes = listOf(IrType.STRING)) { body() }

        assertThatCode(generate(cls))
            .containsOne("data class Ov(val a: String) {")
            .containsOne("override fun toString(): String {")
            .containsOne("\"MY_CUSTOM_REPR\"") // the user's custom body is preserved
            .doesNotContain("component1")
            .doesNotContain("fun copy")
    }

    @Test
    fun dataClassWithCustomEqualsOverrideKeepsIt() {
        // (a, separately) A real data class may override equals; it must survive in the body.
        val cls = irClass("a.Eq", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.method("<init>", argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC) { body() }
        cls.method("component1", returnType = IrType.STRING, accessFlags = Flags.PUBLIC) { body() }
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT)) {
            body(ret(expr(constString("CUSTOM_EQ_MARKER"))))
        }
        cls.method("hashCode", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("toString", returnType = IrType.STRING) { body(ret(expr(constString("s")))) }
        cls.method("copy", returnType = IrType.objectType("a.Eq"), argTypes = listOf(IrType.STRING)) { body() }

        assertThatCode(generate(cls))
            .containsOne("data class Eq(val a: String) {")
            .containsOne("override fun equals(obj: Any?): Boolean {")
            .containsOne("\"CUSTOM_EQ_MARKER\"") // custom equals body preserved
    }

    @Test
    fun destructurableLookalikeWithoutCopyStaysRegularClass() {
        // (b) A hand-written destructurable value class: final, 1:1 ctor, MANUAL componentN with
        // custom bodies + custom equals/hashCode/toString, but NO copy. It must stay a regular
        // class — its custom bodies preserved, and NO `copy` fabricated.
        val cls = irClass("a.Vec", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("x", IrType.INT, Flags.PRIVATE or Flags.FINAL)
        cls.field("y", IrType.INT, Flags.PRIVATE or Flags.FINAL)
        cls.method("<init>", argTypes = listOf(IrType.INT, IrType.INT), accessFlags = Flags.PUBLIC) { body() }
        cls.method("component1", returnType = IrType.INT, accessFlags = Flags.PUBLIC) { body(ret(intLit(111))) }
        cls.method("component2", returnType = IrType.INT, accessFlags = Flags.PUBLIC) { body(ret(intLit(222))) }
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT)) { body(ret(intLit(0))) }
        cls.method("hashCode", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("toString", returnType = IrType.STRING) { body(ret(expr(constString("VEC_REPR")))) }
        // NO copy method.

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("class Vec {")
            // Manual componentN survive as ordinary methods (never dropped, never fabricated copy).
            .containsOne("fun component1(): Int {")
            .containsOne("fun component2(): Int {")
            .doesNotContain("copy")
            // Custom bodies preserved.
            .containsOne("111")
            .containsOne("222")
            .containsOne("\"VEC_REPR\"")
    }

    @Test
    fun noCopyStaysRegularEvenWithFullValueMemberSet() {
        // Detection now REQUIRES copy: componentN + equals/hashCode/toString + matching ctor but no
        // copy ⇒ not a data class (conservative). Guards the misdetection MUST-FIX directly.
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.method("<init>", argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC) { body() }
        cls.method("component1", returnType = IrType.STRING) { body(ret(expr(constString("x")))) }
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT)) { body(ret(intLit(0))) }
        cls.method("hashCode", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("toString", returnType = IrType.STRING) { body(ret(expr(constString("s")))) }

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("class Foo {")
    }

    @Test
    fun copyDefaultSyntheticIsSuppressed() {
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.addCanonicalDataMembers(listOf(IrType.STRING))
        // The synthetic `copy$default(Foo, int, Object)` helper the compiler emits alongside copy().
        cls.method(
            "copy\$default",
            returnType = IrType.objectType("a.Foo"),
            argTypes = listOf(IrType.objectType("a.Foo"), IrType.INT, IrType.OBJECT),
            accessFlags = Flags.PUBLIC or Flags.STATIC,
        ) { body() }

        assertThatCode(generate(cls))
            .containsOne("data class Foo(val a: String) {")
            .doesNotContain("copy")
    }

    // ---- conservative fallbacks (each fails an earlier signal ⇒ stays a regular class) ----

    @Test
    fun regularClassWithoutComponentNStaysRegular() {
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.field("b", IrType.INT, Flags.PRIVATE)
        cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT), accessFlags = Flags.PUBLIC) { body() }
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT)) { body(ret(intLit(0))) }
        cls.method("hashCode", returnType = IrType.INT) { body(ret(intLit(0))) }

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("class Foo {")
    }

    @Test
    fun componentArityMismatchStaysRegular() {
        // Two properties but only component1 ⇒ arity mismatch ⇒ conservative fallback to a regular class.
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.field("b", IrType.INT, Flags.PRIVATE or Flags.FINAL)
        cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT), accessFlags = Flags.PUBLIC) { body() }
        cls.method("component1", returnType = IrType.STRING) { body(ret(expr(constString("x")))) }
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT)) { body(ret(intLit(0))) }
        cls.method("hashCode", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("toString", returnType = IrType.STRING) { body(ret(expr(constString("s")))) }
        cls.method("copy", returnType = IrType.objectType("a.Foo"), argTypes = listOf(IrType.STRING, IrType.INT)) {
            body()
        }

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("class Foo {")
    }

    @Test
    fun extraComponentBeyondArityStaysRegular() {
        // component3 present with only 2 properties ⇒ out-of-range componentN ⇒ not this shape.
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.field("b", IrType.INT, Flags.PRIVATE or Flags.FINAL)
        cls.addCanonicalDataMembers(listOf(IrType.STRING, IrType.INT))
        cls.method("component3", returnType = IrType.INT) { body(ret(intLit(0))) }

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("class Foo {")
    }

    @Test
    fun missingToStringStaysRegular() {
        // component1/2 + equals + hashCode + copy but NO toString ⇒ incomplete generated set ⇒ regular.
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.field("b", IrType.INT, Flags.PRIVATE or Flags.FINAL)
        cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT), accessFlags = Flags.PUBLIC) { body() }
        cls.method("component1", returnType = IrType.STRING) { body(ret(expr(constString("x")))) }
        cls.method("component2", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT)) { body(ret(intLit(0))) }
        cls.method("hashCode", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("copy", returnType = IrType.objectType("a.Foo"), argTypes = listOf(IrType.STRING, IrType.INT)) {
            body()
        }

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("class Foo {")
    }

    @Test
    fun secondaryConstructorStaysRegular() {
        // Two constructors ⇒ can't cleanly reconstruct the primary ⇒ conservative fallback.
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.addCanonicalDataMembers(listOf(IrType.STRING))
        cls.method("<init>", accessFlags = Flags.PUBLIC) { body() } // extra no-arg secondary ctor

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("class Foo {")
    }

    @Test
    fun componentTypeMismatchStaysRegular() {
        // component1 returns the wrong type (Int, not String) ⇒ signal broken ⇒ regular class.
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.method("<init>", argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC) { body() }
        cls.method("component1", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.OBJECT)) { body(ret(intLit(0))) }
        cls.method("hashCode", returnType = IrType.INT) { body(ret(intLit(0))) }
        cls.method("toString", returnType = IrType.STRING) { body(ret(expr(constString("s")))) }
        cls.method("copy", returnType = IrType.objectType("a.Foo"), argTypes = listOf(IrType.STRING)) { body() }

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("class Foo {")
    }

    @Test
    fun userOverloadedComponentSurvivesInRegularClass() {
        // `component1(int)` takes an argument, so it is NOT a generated componentN; in a class that is
        // otherwise not a data class it must survive as a normal method (never silently dropped).
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.field("a", IrType.STRING, Flags.PRIVATE or Flags.FINAL)
        cls.method("component1", returnType = IrType.STRING, argTypes = listOf(IrType.INT)) {
            body(ret(expr(constString("x"))))
        }

        assertThatCode(generate(cls))
            .doesNotContain("data class")
            .containsOne("fun component1(")
    }
}
