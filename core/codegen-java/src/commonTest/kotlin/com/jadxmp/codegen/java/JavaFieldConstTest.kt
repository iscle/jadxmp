package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrFieldConst
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * A `static final` field's compile-time constant is rendered as a declaration initializer, so the
 * field isn't left uninitialized (`static final int X;` → "might not have been initialized").
 */
class JavaFieldConstTest {

    private val staticFinal = Flags.PUBLIC or Flags.STATIC or Flags.FINAL

    private fun fieldClass(name: String, type: IrType, const: IrFieldConst?): String {
        val cls = irClass("a.Foo")
        val field = IrField(cls, name, type, staticFinal).also { it.constValue = const }
        cls.fields.add(field)
        return generate(cls)
    }

    @Test
    fun intConstantInitializer() {
        assertThatCode(fieldClass("CONST_INT", IrType.INT, IrFieldConst.Primitive(255, IrType.INT)))
            .containsOne("public static final int CONST_INT = 255;")
    }

    @Test
    fun stringConstantInitializer() {
        assertThatCode(fieldClass("NAME", IrType.STRING, IrFieldConst.Str("hi")))
            .containsOne("public static final String NAME = \"hi\";")
    }

    @Test
    fun longAndBooleanAndCharConstants() {
        assertThatCode(fieldClass("L", IrType.LONG, IrFieldConst.Primitive(42, IrType.LONG)))
            .containsOne("static final long L = 42L;")
        assertThatCode(fieldClass("B", IrType.BOOLEAN, IrFieldConst.Primitive(1, IrType.BOOLEAN)))
            .containsOne("static final boolean B = true;")
        assertThatCode(fieldClass("C", IrType.CHAR, IrFieldConst.Primitive('x'.code.toLong(), IrType.CHAR)))
            .containsOne("static final char C = 'x';")
    }

    @Test
    fun doubleConstantUsesLiteralFormatting() {
        assertThatCode(fieldClass("D", IrType.DOUBLE, IrFieldConst.Primitive(1.5.toRawBits(), IrType.DOUBLE)))
            .containsOne("static final double D = 0x1.8p0;")
    }

    /**
     * A blank `static final` field (no constant, never assigned) must get its type's default-value
     * initializer, or javac rejects the class ("variable might not have been initialized").
     */
    @Test
    fun blankFinalIntGetsDefaultZero() {
        assertThatCode(fieldClass("C", IrType.INT, null))
            .containsOne("static final int C = 0;")
    }

    @Test
    fun blankFinalReferenceGetsDefaultNull() {
        assertThatCode(fieldClass("REF", IrType.STRING, null))
            .containsOne("static final String REF = null;")
    }

    @Test
    fun blankFinalPrimitivesGetTypedDefaults() {
        assertThatCode(fieldClass("L", IrType.LONG, null)).containsOne("static final long L = 0L;")
        assertThatCode(fieldClass("B", IrType.BOOLEAN, null)).containsOne("static final boolean B = false;")
        assertThatCode(fieldClass("F", IrType.FLOAT, null)).containsOne("static final float F = 0.0f;")
        assertThatCode(fieldClass("D", IrType.DOUBLE, null)).containsOne("static final double D = 0.0;")
        // The JVM default char `` is a control char; emit the numeric `0`, which compiles as a char.
        assertThatCode(fieldClass("CH", IrType.CHAR, null)).containsOne("static final char CH = 0;")
    }

    /** A non-`final` blank field is legal Java as-is (`int i;`); it must NOT get a default initializer. */
    @Test
    fun nonFinalBlankFieldIsUnchanged() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "count", IrType.INT, Flags.PRIVATE or Flags.STATIC))
        assertThatCode(generate(cls))
            .containsOne("static int count;")
            .doesNotContain("count =")
    }

    /**
     * A `final` field ASSIGNED in a reconstructed initializer is definitely-assigned, so codegen emits
     * `C = …;`; it must NOT also receive a redundant `= default` (that would double-init a final).
     */
    @Test
    fun assignedFinalFieldIsNotGivenRedundantDefault() {
        val cls = irClass("a.Foo")
        val fieldRef = FieldRef(IrType.objectType("a.Foo"), "C", IrType.INT)
        cls.fields.add(IrField(cls, "C", IrType.INT, staticFinal))
        cls.method("<clinit>", accessFlags = Flags.STATIC or Flags.FINAL) {
            body(FieldInstruction(fieldRef, isStatic = true, isPut = true, args = listOf(intLit(7))))
        }
        // The declaration stays a blank final; the value is assigned in the static initializer, so no
        // `= 0` default is added on the declaration line.
        assertThatCode(generate(cls))
            .containsOne("static final int C;")
    }
}
