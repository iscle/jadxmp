package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrFieldConst
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinClassTest {

    /** Attach a nested class (its binary name is `$`-joined) under [outer], wiring both links. */
    private fun IrClass.nest(binaryName: String, accessFlags: Int): IrClass {
        val inner = IrClass(root, binaryName, accessFlags)
        root.addClass(inner)
        innerClasses.add(inner)
        inner.outerClass = this
        return inner
    }

    @Test
    fun finalClassShellAndPackage() {
        val cls = irClass("com.example.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL)
        assertThatCode(generate(cls))
            .containsOne("package com.example")
            .containsOne("class Foo {")
            .doesNotContain("open")
            .doesNotContain(";")
    }

    @Test
    fun nonFinalClassIsOpen() {
        // Kotlin classes are final by default; a non-final extendable Java class must be `open`.
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC)
        assertThatCode(generate(cls)).containsOne("open class Foo {")
    }

    @Test
    fun abstractClass() {
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        assertThatCode(generate(cls))
            .containsOne("abstract class Foo {")
            .doesNotContain("open")
    }

    @Test
    fun interfaceShell() {
        val cls = irClass("a.Bar", accessFlags = Flags.PUBLIC or Flags.ABSTRACT or Flags.INTERFACE)
        assertThatCode(generate(cls))
            .containsOne("interface Bar {")
            .doesNotContain("abstract")
            .doesNotContain("class")
    }

    @Test
    fun annotationClassShell() {
        val cls = irClass("a.Ann", accessFlags = Flags.PUBLIC or Flags.ANNOTATION or Flags.INTERFACE)
        assertThatCode(generate(cls)).containsOne("annotation class Ann {")
    }

    @Test
    fun enumClassWithEntries() {
        val cls = irClass("a.Color", accessFlags = Flags.PUBLIC or Flags.FINAL or Flags.ENUM)
        val self = IrType.objectType("a.Color")
        // Real enum constants carry ACC_ENUM (Flags.ENUM); the detector gates on that flag (S1).
        cls.fields.add(IrField(cls, "RED", self, Flags.PUBLIC or Flags.STATIC or Flags.FINAL or Flags.ENUM))
        cls.fields.add(IrField(cls, "GREEN", self, Flags.PUBLIC or Flags.STATIC or Flags.FINAL or Flags.ENUM))
        cls.fields.add(IrField(cls, "\$VALUES", IrType.array(self), Flags.STATIC or Flags.FINAL))
        assertThatCode(generate(cls))
            .containsOne("enum class Color {")
            .containsLine(1, "RED,")
            .containsLine(1, "GREEN")
            // The synthetic backing array is not emitted, and constants are entries, not properties.
            .doesNotContain("VALUES")
            .doesNotContain("val RED")
    }

    @Test
    fun objectSingletonDetectedFromInstanceField() {
        val cls = irClass("a.Obj", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.fields.add(IrField(cls, "INSTANCE", IrType.objectType("a.Obj"), Flags.PUBLIC or Flags.STATIC or Flags.FINAL))
        assertThatCode(generate(cls))
            .containsOne("object Obj {")
            // The singleton self-reference is implicit in Kotlin, not a property.
            .doesNotContain("INSTANCE")
            .doesNotContain("class Obj")
    }

    @Test
    fun supertypeListWithSuperConstructorCallAndInterface() {
        val cls = irClass(
            "com.example.Foo",
            accessFlags = Flags.PUBLIC or Flags.FINAL,
            superType = IrType.objectType("a.Base"),
            interfaces = listOf(IrType.objectType("a.Iface")),
        )
        assertThatCode(generate(cls))
            .containsOne("import a.Base")
            .containsOne("import a.Iface")
            // super class carries `()`, interfaces do not.
            .containsOne("class Foo : Base(), Iface {")
    }

    @Test
    fun objectSuperclassOmitted() {
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.FINAL, superType = IrType.OBJECT)
        assertThatCode(generate(cls))
            .containsOne("class Foo {")
            .doesNotContain(":")
    }

    @Test
    fun interfaceExtendsUsesColonWithoutParens() {
        val cls = irClass(
            "a.I",
            accessFlags = Flags.PUBLIC or Flags.ABSTRACT or Flags.INTERFACE,
            interfaces = listOf(IrType.objectType("a.Base")),
        )
        assertThatCode(generate(cls))
            .containsOne("interface I : Base {")
            .doesNotContain("Base()")
    }

    @Test
    fun instancePropertiesAreCompilableOrHonestlyMarked() {
        // M2: a property without a reconstructed initializer must never be silently uncompilable.
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "count", IrType.INT, Flags.PRIVATE)) // var primitive → JVM-default init
        cls.fields.add(IrField(cls, "label", IrType.STRING, Flags.PRIVATE)) // var ref → lateinit
        cls.fields.add(IrField(cls, "name", IrType.STRING, Flags.PUBLIC or Flags.FINAL)) // val ref → marked
        assertThatCode(generate(cls))
            .containsLine(1, "private var count: Int = 0")
            .containsLine(1, "private lateinit var label: String")
            .containsOne("// JADXMP ERROR: field initializer not reconstructed")
            .containsLine(1, "val name: String")
    }

    @Test
    fun constValInCompanionForStaticFinalConstant() {
        val cls = irClass("a.Foo")
        val max = IrField(cls, "MAX", IrType.INT, Flags.PUBLIC or Flags.STATIC or Flags.FINAL)
        max.constValue = IrFieldConst.Primitive(255L, IrType.INT)
        val name = IrField(cls, "TAG", IrType.STRING, Flags.PUBLIC or Flags.STATIC or Flags.FINAL)
        name.constValue = IrFieldConst.Str("hi")
        cls.fields.add(max)
        cls.fields.add(name)
        assertThatCode(generate(cls))
            .containsOne("companion object {")
            .containsOne("const val MAX: Int = 255")
            .containsOne("const val TAG: String = \"hi\"")
    }

    @Test
    fun funSignatureReturnAndParams() {
        val cls = irClass("a.Foo")
        cls.method(
            "compute",
            returnType = IrType.INT,
            argTypes = listOf(IrType.STRING, IrType.INT),
            accessFlags = Flags.PUBLIC,
        ) {
            this[CodegenKeys.PARAM_NAMES] = listOf("name", "size")
            body(ret(intLit(0)))
        }
        assertThatCode(generate(cls))
            .containsOne("fun compute(name: String, size: Int): Int {")
            .containsOne("return 0")
    }

    @Test
    fun unitReturnIsOmitted() {
        val cls = irClass("a.Foo")
        cls.method("run") { body() }
        assertThatCode(generate(cls))
            .containsOne("fun run() {")
            .doesNotContain(": Unit")
    }

    @Test
    fun overrideFromAttribute() {
        val cls = irClass("a.Foo")
        cls.method("foo") {
            this[KotlinCodegenKeys.IS_OVERRIDE] = true
            body()
        }
        assertThatCode(generate(cls)).containsOne("override fun foo() {")
    }

    @Test
    fun overrideAutoDetectedForAnyMembers() {
        val cls = irClass("a.Foo")
        cls.method("toString", returnType = IrType.STRING) { body(ret(expr(constString("x")))) }
        assertThatCode(generate(cls)).containsOne("override fun toString(): String {")
    }

    @Test
    fun constructorRendersAsConstructorKeyword() {
        val cls = irClass("a.Foo")
        cls.method("<init>", argTypes = listOf(IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body()
        }
        assertThatCode(generate(cls))
            .containsOne("constructor(value: Int) {")
            .doesNotContain("<init>")
            .doesNotContain("fun ")
    }

    @Test
    fun abstractMethodHasNoBody() {
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        cls.method("run", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        assertThatCode(generate(cls))
            .containsOne("abstract fun run()")
            .doesNotContain("run() {")
    }

    @Test
    fun staticInitializerBecomesCompanionInit() {
        val cls = irClass("a.Foo")
        cls.method("<clinit>", accessFlags = Flags.STATIC) {
            body(assign(Local(1, IrType.INT).ref(), Instruction0Const()))
        }
        assertThatCode(generate(cls))
            .containsOne("companion object {")
            .containsOne("init {")
    }

    @Test
    fun staticMethodGoesToCompanion() {
        val cls = irClass("a.Foo")
        cls.method("create", returnType = IrType.objectType("a.Foo"), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(constString("x"))))
        }
        assertThatCode(generate(cls))
            .containsOne("companion object {")
            .containsOne("fun create(): Foo {")
    }

    @Test
    fun staticNestedClassIsPlainNestedClass() {
        val root = IrRoot()
        val outer = irClass("com.example.Outer", accessFlags = Flags.PUBLIC or Flags.FINAL, root = root)
        outer.nest("com.example.Outer\$Inner", Flags.PRIVATE or Flags.STATIC or Flags.FINAL)
        assertThatCode(generate(outer))
            .containsOne("class Outer {")
            // A static nested Java class is a plain nested Kotlin class, NOT `inner class`.
            .containsLine(1, "private class Inner {")
            .doesNotContain("inner class")
            .doesNotContain("Outer\$Inner")
    }

    private fun Instruction0Const() =
        com.jadxmp.ir.insn.Instruction(com.jadxmp.ir.insn.IrOpcode.CONST, args = listOf(intLit(0)))
}
