package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reserved-word / invalid-character identifier renaming at every emission site, so obfuscated names
 * produce compilable Java. Definitions and references must agree on the sanitized spelling.
 */
class JavaNamesTest {

    @Test
    fun classNamedWithReservedWord() {
        val cls = irClass("pkg.do")
        assertThatCode(generate(cls))
            .containsOne("class doWord {")
            .doesNotContain("class do ")
    }

    @Test
    fun classNameWithInvalidCharacter() {
        val cls = irClass("pkg.Foo-Bar")
        assertThatCode(generate(cls)).containsOne("class Foo_Bar {")
    }

    @Test
    fun packageSegmentsAreSanitizedKeepingStructure() {
        val cls = irClass("com.do.int.Foo")
        assertThatCode(generate(cls))
            .containsOne("package com.doWord.intWord;")
            .containsOne("class Foo {")
    }

    @Test
    fun fieldAndMethodNamedWithReservedWords() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "int", IrType.INT, Flags.PRIVATE))
        cls.method("new", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        assertThatCode(generate(cls))
            .containsOne("private int intWord;")
            .containsOne("void newWord();")
    }

    @Test
    fun parameterNamedWithReservedWord() {
        val cls = irClass("a.Foo")
        cls.method("m", argTypes = listOf(IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("for")
            body()
        }
        assertThatCode(generate(cls)).containsOne("void m(int forWord) {")
    }

    @Test
    fun localVariableNamedWithReservedWordMatchesAtDeclarationAndUse() {
        val v = Local(0, IrType.INT, name = "class")
        val cls = irClass("a.Foo")
        cls.method("m") {
            body(
                assign(v.ref(), com.jadxmp.ir.insn.Instruction(com.jadxmp.ir.insn.IrOpcode.CONST, args = listOf(intLit(1)))),
                // A second use so declaration and reference must spell the same sanitized name.
                assign(Local(1, IrType.INT).ref(), arith(com.jadxmp.ir.insn.ArithOp.ADD, v.ref(), intLit(2))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("int classWord = 1;")
            .containsOne("classWord + 2")
            .doesNotContain("int class ")
    }

    @Test
    fun fieldReferenceUsesSanitizedName() {
        val foo = IrType.objectType("a.Foo")
        val self = Local(0, foo, isThis = true)
        val cls = irClass("a.Foo")
        cls.method("m") {
            thisArg = self.ssaValue
            body(instancePut(self.ref(), intLit(5), FieldRef(foo, "synchronized", IrType.INT)))
        }
        assertThatCode(generate(cls)).containsOne("this.synchronizedWord = 5;")
    }

    @Test
    fun crossClassReferenceToRenamedClassMatchesDefinition() {
        // Class `pkg.do` (renamed `doWord`) is the superclass of `pkg.B`; the `extends` reference must
        // spell the exact same sanitized name the definition uses.
        val superclass = irClass("pkg.do")
        val sub = irClass("pkg.B", superType = IrType.objectType("pkg.do"))
        assertThatCode(generate(superclass)).containsOne("class doWord {")
        assertThatCode(generate(sub)).containsOne("class B extends doWord {")
    }

    @Test
    fun duplicatedFieldNamesAreDisambiguatedAndReferencesResolveByType() {
        // Two fields share the raw name `fieldName` (legal in .dex, illegal in Java). The first keeps the
        // sanitized base; the second is suffixed. Each field access resolves to the field whose TYPE it
        // names, so the two reads render the two different aliases (reference consistency).
        val dupType = IrType.objectType("a.Dup")
        val self = Local(0, dupType, isThis = true)
        val cls = irClass("a.Dup")
        cls.fields.add(IrField(cls, "fieldName", IrType.STRING, Flags.PUBLIC))
        cls.fields.add(IrField(cls, "fieldName", IrType.OBJECT, Flags.PUBLIC))
        cls.method("readStr", returnType = IrType.STRING) {
            thisArg = self.ssaValue
            body(ret(expr(instanceGet(self.ref(), FieldRef(dupType, "fieldName", IrType.STRING)))))
        }
        cls.method("readObj", returnType = IrType.OBJECT) {
            thisArg = self.ssaValue
            body(ret(expr(instanceGet(self.ref(), FieldRef(dupType, "fieldName", IrType.OBJECT)))))
        }
        assertThatCode(generate(cls))
            .containsOne("public String fieldName;")
            .containsOne("public Object fieldName2;")
            // The String read resolves to the first field (base name); the Object read to the suffixed one.
            .containsOne("return this.fieldName;")
            .containsOne("return this.fieldName2;")
    }

    @Test
    fun duplicatedMethodNamesAreDisambiguatedAndCallsResolveByReturnType() {
        // Two methods share name AND parameter types, differing only by return type (legal in bytecode,
        // an "already defined" error in Java). The later one is renamed; a call resolves to the exact
        // signature, so `run2` is invoked for the Object-returning one.
        val dupType = IrType.objectType("a.Dup")
        val cls = irClass("a.Dup")
        cls.method("run", returnType = IrType.STRING, accessFlags = Flags.PUBLIC or Flags.STATIC) { body() }
        cls.method("run", returnType = IrType.OBJECT, accessFlags = Flags.PUBLIC or Flags.STATIC) { body() }
        cls.method("call", accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(staticInvoke(dupType, "run", IrType.OBJECT, emptyList(), emptyList()))
        }
        assertThatCode(generate(cls))
            .containsOne("static String run() {")
            .containsOne("static Object run2() {")
            .containsOne("Dup.run2();")
    }

    @Test
    fun legalOverloadsKeepTheSameName() {
        // Same name, DIFFERENT parameter types is a real Java overload — it must NOT be renamed.
        val cls = irClass("a.Over")
        cls.method("foo", argTypes = listOf(IrType.INT), accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        cls.method("foo", argTypes = listOf(IrType.LONG), accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        assertThatCode(generate(cls))
            .containsOne("void foo(int ")
            .containsOne("void foo(long ")
            .doesNotContain("foo2")
    }

    @Test
    fun crossClassReferenceToDuplicatedFieldResolvesAgainstItsOwnClass() {
        // A field read of a duplicated field declared in ANOTHER class must still render the alias that
        // class's definition uses — proving the reference resolves against the referenced class's own
        // member list (via the shared model root), not the referencing class.
        val root = IrRoot()
        val holderType = IrType.objectType("a.Holder")
        val holder = irClass("a.Holder", root = root)
        holder.fields.add(IrField(holder, "value", IrType.STRING, Flags.PUBLIC))
        holder.fields.add(IrField(holder, "value", IrType.OBJECT, Flags.PUBLIC))
        val user = irClass("a.User", root = root)
        val h = Local(1, holderType)
        user.method("read", returnType = IrType.OBJECT) {
            body(ret(expr(instanceGet(h.ref(), FieldRef(holderType, "value", IrType.OBJECT)))))
        }
        assertThatCode(generate(user)).containsOne(".value2;")
    }

    @Test
    fun inheritedMemberReferenceRendersDeclaringSuperclassAlias() {
        // Rule-4 guard: the super `MSup` has BOTH a return-only method collision (run -> run/run2) and a
        // duplicate field (value -> value/value2). `MSub` inherits them and declares nothing. `MUser`
        // references the INHERITED members through the subclass static type (declaringType = MSub). The
        // reference must resolve up the in-model hierarchy to MSup's node and render MSup's SUFFIXED alias,
        // so the call/read binds the intended member — never the base name (which is a DIFFERENT member).
        val root = IrRoot()
        val subType = IrType.objectType("a.MSub")
        val sup = irClass("a.MSup", root = root)
        sup.method("run", returnType = IrType.STRING, accessFlags = Flags.PUBLIC or Flags.STATIC) { body() }
        sup.method("run", returnType = IrType.OBJECT, accessFlags = Flags.PUBLIC or Flags.STATIC) { body() }
        sup.fields.add(IrField(sup, "value", IrType.STRING, Flags.PUBLIC or Flags.STATIC))
        sup.fields.add(IrField(sup, "value", IrType.OBJECT, Flags.PUBLIC or Flags.STATIC))
        irClass("a.MSub", superType = IrType.objectType("a.MSup"), root = root)
        val user = irClass("a.MUser", root = root)
        user.method("callInherited", returnType = IrType.OBJECT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticInvoke(subType, "run", IrType.OBJECT, emptyList(), emptyList(), reg(-1, IrType.OBJECT)))))
        }
        user.method("readInherited", returnType = IrType.OBJECT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticGet(FieldRef(subType, "value", IrType.OBJECT), reg(-1, IrType.OBJECT)))))
        }
        assertThatCode(generate(user))
            .containsOne("MSub.run2();")
            .containsOne("MSub.value2;")
            // The base names would bind the WRONG (String) members — they must not appear as the reference.
            .doesNotContain("MSub.run()")
            .doesNotContain("MSub.value;")
    }

    // ---- source name (file path) ⇄ class body agreement -----------------------------------------
    //
    // The output `.java` file path is derived from JavaCodeGenerator.sourceName; it MUST equal the name
    // the class body declares, or a valid class (e.g. `class doWord`) is written to the wrong file
    // (`do.java`) and fails to recompile. Each test asserts the sourceName AND that the body agrees.

    @Test
    fun reservedClassNameSourceNameMatchesBody() {
        // Binary `do` (a reserved word) → file/simple name `doWord`, exactly what the body declares.
        val cls = irClass("do")
        assertEquals("doWord", JavaCodeGenerator.sourceName(cls))
        assertBodyDeclares(cls, expectedSimpleName = "doWord", expectedPackageLine = null)
    }

    @Test
    fun reservedPackageSegmentsSourceNameMatchesBody() {
        // Binary `do.if.A` → sanitized package path `doWord.ifWord`, class `A`.
        val cls = irClass("do.if.A")
        assertEquals("doWord.ifWord.A", JavaCodeGenerator.sourceName(cls))
        assertBodyDeclares(cls, expectedSimpleName = "A", expectedPackageLine = "package doWord.ifWord;")
    }

    @Test
    fun invalidCharClassNameSourceNameMatchesBody() {
        // Binary `do-` (illegal `-`) → `do_`; and `i-f` → `i_f`. Default package, no package line.
        val a = irClass("do-")
        assertEquals("do_", JavaCodeGenerator.sourceName(a))
        assertBodyDeclares(a, expectedSimpleName = "do_", expectedPackageLine = null)

        val b = irClass("i-f")
        assertEquals("i_f", JavaCodeGenerator.sourceName(b))
        assertBodyDeclares(b, expectedSimpleName = "i_f", expectedPackageLine = null)
    }

    @Test
    fun collidingTopLevelClassesGetDistinctSourceNamesAndBodiesAgree() {
        // Two DISTINCT binary names that sanitize to the same identifier (`a-b`, `a_b` → `a_b`) in one
        // package. Declaration order decides: first keeps the base, the second is suffixed — and each
        // body declares the SAME name as its file, so the two files never collide.
        val root = IrRoot()
        val first = irClass("pkg.a-b", root = root)
        val second = irClass("pkg.a_b", root = root)
        assertEquals("pkg.a_b", JavaCodeGenerator.sourceName(first))
        assertEquals("pkg.a_b2", JavaCodeGenerator.sourceName(second))
        assertBodyDeclares(first, expectedSimpleName = "a_b", expectedPackageLine = "package pkg;")
        assertBodyDeclares(second, expectedSimpleName = "a_b2", expectedPackageLine = "package pkg;")
    }

    @Test
    fun collisionIsScopedToTheSamePackage() {
        // Same sanitized simple name in DIFFERENT packages is not a collision — neither is suffixed.
        val root = IrRoot()
        val a = irClass("one.a-b", root = root)
        val b = irClass("two.a-b", root = root)
        assertEquals("one.a_b", JavaCodeGenerator.sourceName(a))
        assertEquals("two.a_b", JavaCodeGenerator.sourceName(b))
    }

    @Test
    fun classSimpleNameReusingAPackageSegmentAgreesWithBody() {
        // `a.b.b` — the simple name `b` repeats a package segment. This is legal Java (FQN `a.b.b`); the
        // point is that the file path and body are computed identically, so they never disagree.
        val cls = irClass("a.b.b")
        assertEquals("a.b.b", JavaCodeGenerator.sourceName(cls))
        assertBodyDeclares(cls, expectedSimpleName = "b", expectedPackageLine = "package a.b;")
    }

    @Test
    fun normalClassSourceNameIsTheBinaryName() {
        // No spurious renames: a fully valid name is returned verbatim (== binary IrClass.fullName).
        val cls = irClass("com.example.Widget")
        assertEquals("com.example.Widget", JavaCodeGenerator.sourceName(cls))
        assertEquals(cls.fullName, JavaCodeGenerator.sourceName(cls))
    }

    @Test
    fun constructorNameMatchesTheEmittedClassName() {
        // A reserved-named class's constructor must be renamed in lockstep with the class, or the body is
        // uncompilable (`doWord { do() {} }`).
        val cls = irClass("do")
        cls.method(com.jadxmp.ir.insn.MethodRef.CONSTRUCTOR_NAME) { body() }
        assertThatCode(generate(cls))
            .containsOne("class doWord {")
            .containsOne("doWord() {")
            .doesNotContain("do() {")
    }

    /** Assert the generated body declares `class <expectedSimpleName>` and the expected package line. */
    private fun assertBodyDeclares(cls: IrClass, expectedSimpleName: String, expectedPackageLine: String?) {
        val code = generate(cls)
        val expectedSimple = JavaCodeGenerator.sourceName(cls).substringAfterLast('.')
        assertEquals(expectedSimpleName, expectedSimple, "sourceName simple segment must match the body")
        assertThatCode(code).containsOne("class $expectedSimpleName {")
        if (expectedPackageLine != null) {
            assertThatCode(code).containsOne(expectedPackageLine)
        } else {
            assertTrue(!code.contains("package "), "default-package class must emit no package line:\n$code")
        }
    }

    @Test
    fun normalIdentifiersAreUntouched() {
        val cls = irClass("com.example.Widget")
        cls.fields.add(IrField(cls, "count", IrType.INT, Flags.PRIVATE))
        cls.method("render", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        assertThatCode(generate(cls))
            .containsOne("package com.example;")
            .containsOne("class Widget {")
            .containsOne("private int count;")
            .containsOne("void render();")
    }
}
