package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaClassTest {

    /** Attach a nested class (its binary name is `$`-joined) under [outer], wiring both links. */
    private fun IrClass.nest(binaryName: String, accessFlags: Int): IrClass {
        val inner = IrClass(root, binaryName, accessFlags)
        root.addClass(inner)
        innerClasses.add(inner)
        inner.outerClass = this
        return inner
    }

    @Test
    fun emitsNestedClassInsideOuterKeepingMemberModifiers() {
        val root = IrRoot()
        val outer = irClass("com.example.Outer", accessFlags = Flags.PUBLIC or Flags.FINAL, root = root)
        // A private static nested class: those member-only modifiers are LEGAL on a nested class and
        // must survive (unlike on a top-level class, where they'd be stripped).
        val inner = outer.nest("com.example.Outer\$Inner", Flags.PRIVATE or Flags.STATIC or Flags.FINAL)
        inner.fields.add(IrField(inner, "VALUE", IrType.INT, Flags.PUBLIC or Flags.STATIC or Flags.FINAL))

        assertThatCode(generate(outer))
            .containsOne("public final class Outer {")
            // Nested one indent level in, emitted by its SIMPLE name with member modifiers intact.
            .containsLine(1, "private static final class Inner {")
            // A blank `static final` gets its type's default initializer so the class recompiles.
            .containsLine(2, "public static final int VALUE = 0;")
            // The binary `Outer$Inner` name never appears in the emitted source text.
            .doesNotContain("Outer\$Inner")
            .doesNotContain("class Inner extends")
    }

    @Test
    fun nestedClassIsNotEmittedAsSeparateTopLevelUnit() {
        val root = IrRoot()
        val outer = irClass("a.Outer", root = root)
        outer.nest("a.Outer\$Inner", Flags.PUBLIC or Flags.STATIC)
        // One top-level `class` opener for Outer, and exactly one nested `class Inner` — no duplicate.
        assertThatCode(generate(outer))
            .containsOne("class Outer {")
            .containsOne("class Inner {")
    }

    @Test
    fun emitsMultiLevelNesting() {
        val root = IrRoot()
        val outer = irClass("a.Outer", root = root)
        val inner = outer.nest("a.Outer\$Inner", Flags.PUBLIC or Flags.STATIC)
        inner.nest("a.Outer\$Inner\$Deep", Flags.PUBLIC or Flags.STATIC)
        assertThatCode(generate(outer))
            .containsLine(0, "public class Outer {")
            .containsLine(1, "public static class Inner {")
            .containsLine(2, "public static class Deep {")
    }

    @Test
    fun packageImportsAndClassShell() {
        val cls = irClass(
            "com.example.Foo",
            accessFlags = Flags.PUBLIC or Flags.FINAL,
            superType = IrType.objectType("java.util.ArrayList"),
            interfaces = listOf(IrType.objectType("java.io.Serializable")),
        )
        assertThatCode(generate(cls))
            .containsOne("package com.example;")
            .containsOne("import java.io.Serializable;")
            .containsOne("import java.util.ArrayList;")
            .containsOne("public final class Foo extends ArrayList implements Serializable {")
    }

    @Test
    fun objectSuperclassIsOmitted() {
        val cls = irClass("a.Foo", superType = IrType.OBJECT)
        assertThatCode(generate(cls))
            .containsOne("public class Foo {")
            .doesNotContain("extends")
    }

    @Test
    fun interfaceKeywordAndImplicitAbstract() {
        val cls = irClass("a.Bar", accessFlags = Flags.PUBLIC or Flags.ABSTRACT or Flags.INTERFACE)
        assertThatCode(generate(cls))
            .containsOne("public interface Bar {")
            .doesNotContain("abstract")
    }

    @Test
    fun fieldsWithModifiers() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "count", IrType.INT, Flags.PRIVATE))
        cls.fields.add(IrField(cls, "NAME", IrType.STRING, Flags.PUBLIC or Flags.STATIC or Flags.FINAL))
        assertThatCode(generate(cls))
            .containsOne("private int count;")
            // A blank `static final` gets its type's default initializer (here `null`) so it recompiles.
            .containsOne("public static final String NAME = null;")
    }

    @Test
    fun methodSignatureWithParamsReturnAndThrows() {
        val cls = irClass("a.Foo")
        cls.method(
            "compute",
            returnType = IrType.INT,
            argTypes = listOf(IrType.STRING, IrType.INT),
            accessFlags = Flags.PUBLIC,
        ) {
            this[CodegenKeys.PARAM_NAMES] = listOf("name", "size")
            this[CodegenKeys.THROWS] = listOf(IrType.objectType("java.io.IOException"))
            body(ret(intLit(0)))
        }
        assertThatCode(generate(cls))
            .containsOne("public int compute(String name, int size) throws IOException {")
            .containsOne("return 0;")
    }

    @Test
    fun abstractMethodHasNoBody() {
        val cls = irClass("a.Foo", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        cls.method("run", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        assertThatCode(generate(cls))
            .containsOne("public abstract void run();")
            .doesNotContain("run() {")
    }

    @Test
    fun constructorUsesClassNameAndNoReturnType() {
        val cls = irClass("a.Foo")
        cls.method("<init>", argTypes = listOf(IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body()
        }
        assertThatCode(generate(cls))
            .containsOne("public Foo(int value) {")
            .doesNotContain("void Foo")
    }

    @Test
    fun defaultParamNamesFromTypeWhenUnspecified() {
        val cls = irClass("a.Foo")
        cls.method("m", argTypes = listOf(IrType.STRING, IrType.STRING)) { body() }
        // Two String params must get distinct generated names.
        assertThatCode(generate(cls))
            .containsOne("void m(String str, String str2) {")
    }

    @Test
    fun staticInitializerRendersAsStaticBlock() {
        val cls = irClass("a.Foo")
        cls.method("<clinit>", accessFlags = Flags.STATIC) { body() }
        assertThatCode(generate(cls)).containsOne("static {")
    }
}
