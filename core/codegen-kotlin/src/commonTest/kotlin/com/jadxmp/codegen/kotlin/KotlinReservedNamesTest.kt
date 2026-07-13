package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * Reserved-word / invalid-character identifier handling for the Kotlin backend
 * (defends corpus names samples: a class `do`, a field `do`/`0f`, a method `try`, a param `object`).
 *
 * Kotlin lets any illegal identifier be written **backtick-quoted** (`` `do` ``), so a hard keyword,
 * a digit-started name, or an empty name is escaped rather than sanitized; characters that are illegal
 * even inside backticks (`-`, `.`) are replaced with `_`. The mapping is a pure function of the input,
 * so a definition and every reference to it agree on the spelling (see the consistency tests below).
 * A normal identifier must NEVER be needlessly escaped.
 */
class KotlinReservedNamesTest {

    @Test
    fun classNamedHardKeywordIsBacktickEscaped() {
        val cls = irClass("do", accessFlags = Flags.PUBLIC or Flags.FINAL)
        assertThatCode(generate(cls))
            .containsOne("class `do` {")
            // A hard keyword class name must never be emitted raw (invalid Kotlin).
            .doesNotContain("class do ")
    }

    @Test
    fun fieldNamesReservedAndDigitStartAreEscaped() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "do", IrType.STRING, Flags.PUBLIC)) // hard keyword
        cls.fields.add(IrField(cls, "0f", IrType.STRING, Flags.PUBLIC)) // digit start (legal charset)
        assertThatCode(generate(cls))
            .containsOne("`do`")
            .containsOne("`0f`")
    }

    @Test
    fun methodNamedHardKeywordIsEscaped() {
        val cls = irClass("a.Foo")
        cls.method("try", returnType = IrType.STRING, accessFlags = Flags.PUBLIC) {
            body(ret(expr(constString("x"))))
        }
        assertThatCode(generate(cls))
            .containsOne("fun `try`(): String {")
            .doesNotContain("fun try(")
    }

    @Test
    fun paramNamedHardKeywordIsEscaped() {
        val cls = irClass("a.Foo")
        cls.method("run", argTypes = listOf(IrType.INT), accessFlags = Flags.PUBLIC) {
            this[CodegenKeys.PARAM_NAMES] = listOf("object")
            body()
        }
        assertThatCode(generate(cls))
            .containsOne("fun run(`object`: Int) {")
    }

    /**
     * A local with a debug name that is a hard keyword is emitted as a VALID Kotlin identifier (a safe
     * rename — a local is not part of the API, so jadx-style renaming loses nothing). It must never be a
     * bare keyword. Current form is `_val_` (the escape survives the shared allocator as underscores).
     */
    @Test
    fun localVarNamedHardKeywordIsValidKotlin() {
        val cls = irClass("a.Foo")
        cls.method("run", accessFlags = Flags.PUBLIC) {
            val local = Local(1, IrType.INT, name = "val")
            body(
                assign(local.ref(), Instruction0Const()),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("_val_")
            // Never a bare hard keyword used as a declared identifier.
            .doesNotContain("var val")
    }

    /**
     * A generated (type-derived) local name that de-capitalises to a Kotlin hard keyword — a class named
     * `In` suggests base `in` — must be backtick-escaped, not emitted as the bare invalid keyword `in`.
     */
    @Test
    fun generatedKeywordLocalNameIsBacktickEscaped() {
        val inType = IrType.objectType("In")
        val v = Local(2, inType) // no debug name → type-derived fallback base "in"
        val cls = irClass("a.Foo")
        cls.method("run", accessFlags = Flags.PUBLIC) {
            body(
                assign(v.ref(), newInstance(inType)),
                // Two uses keep the value materialised as a declared local (not inlined away).
                virtualInvoke(v.ref(), inType, "a", IrType.VOID, emptyList()),
                virtualInvoke(v.ref(), inType, "b", IrType.VOID, emptyList()),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            // Declaration and both uses render the escaped form (never the bare keyword).
            .countString(3, "`in`")
            .doesNotContain("var in ")
            .doesNotContain(" in.")
    }

    /** A name whose characters are illegal even inside backticks (`-`) is sanitized to `_`, not escaped. */
    @Test
    fun invalidCharNameIsSanitizedNotBacktickQuoted() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "a-b", IrType.INT, Flags.PUBLIC))
        assertThatCode(generate(cls))
            .containsOne("a_b")
            .doesNotContain("`a-b`")
            .doesNotContain("a-b")
    }

    /** Reference consistency: reading an escaped field renders the SAME `` `do` `` as its definition. */
    @Test
    fun fieldReferenceMatchesEscapedDefinition() {
        val self = IrType.objectType("a.Foo")
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "do", IrType.STRING, Flags.PUBLIC))
        cls.method("read", returnType = IrType.STRING, accessFlags = Flags.PUBLIC) {
            val thisRef = Local(0, self, isThis = true)
            body(
                assign(reg(-1, IrType.STRING), instanceGet(thisRef.ref(), FieldRef(self, "do", IrType.STRING))),
                ret(reg(-1, IrType.STRING)),
            )
        }
        // The escaped spelling appears at least twice: the property definition and the read reference.
        assertThatCode(generate(cls)).countString(2, "`do`")
    }

    /** Reference consistency: a class named `do` used as a type renders escaped where referenced. */
    @Test
    fun classTypeReferenceIsEscapedConsistently() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "held", IrType.objectType("do"), Flags.PUBLIC))
        assertThatCode(generate(cls))
            // The referenced type keyword class is escaped (as an import and/or use-site).
            .containsOne("`do`")
            .doesNotContain(": do")
    }

    /** Guard against over-escaping: ordinary identifiers stay bare (no stray backticks anywhere). */
    @Test
    fun normalIdentifiersAreNotEscaped() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "count", IrType.INT, Flags.PRIVATE))
        cls.method("compute", returnType = IrType.INT, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC) {
            this[CodegenKeys.PARAM_NAMES] = listOf("name")
            body(ret(intLit(0)))
        }
        assertThatCode(generate(cls))
            .containsOne("class Foo {")
            .containsOne("fun compute(name: String): Int {")
            .doesNotContain("`") // NOT a single backtick in the whole output
    }

    private fun Instruction0Const() =
        com.jadxmp.ir.insn.Instruction(com.jadxmp.ir.insn.IrOpcode.CONST, args = listOf(intLit(0)))
}
