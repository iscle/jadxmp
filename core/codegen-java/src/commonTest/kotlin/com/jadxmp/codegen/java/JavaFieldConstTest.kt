package com.jadxmp.codegen.java

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

    @Test
    fun fieldWithoutConstValueHasNoInitializer() {
        assertThatCode(fieldClass("PLAIN", IrType.INT, null))
            .containsOne("static final int PLAIN;")
            .doesNotContain("PLAIN =")
    }
}
