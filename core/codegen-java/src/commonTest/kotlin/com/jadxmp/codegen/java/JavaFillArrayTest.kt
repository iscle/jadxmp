package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.FillArrayInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * `fill-array-data` codegen: jadx renders a `// fill-array-data instruction` comment then one
 * `arr[i] = value;` assignment per decoded element, with each literal formatted by the array's element
 * type (byte/short/int/long/char/float/double, signed). See jadx `InsnGen.fillArray`.
 */
class JavaFillArrayTest {

    /** A `long[]` fill: elements render as long literals against `jArr[i]`. */
    @Test
    fun longArrayFillRendersPerElementAssignments() {
        val cls = irClass("a.Foo")
        val arr = Local(1, IrType.array(IrType.LONG), name = "jArr", isParam = true)
        cls.method("m", argTypes = listOf(IrType.array(IrType.LONG))) {
            body(FillArrayInstruction(elementWidth = 8, elements = longArrayOf(1, 2), array = arr.ref()))
        }
        assertThatCode(generate(cls))
            .containsOne("// fill-array-data instruction")
            .containsOne("jArr[0] = 1L;")
            .containsOne("jArr[1] = 2L;")
    }

    /** A `byte[]` fill preserves signed values (0xFF → -1) and correct index order. */
    @Test
    fun byteArrayFillIsSignedAndOrdered() {
        val cls = irClass("a.Foo")
        val arr = Local(1, IrType.array(IrType.BYTE), name = "bArr", isParam = true)
        cls.method("m", argTypes = listOf(IrType.array(IrType.BYTE))) {
            body(FillArrayInstruction(elementWidth = 1, elements = longArrayOf(-1, 0, 127), array = arr.ref()))
        }
        assertThatCode(generate(cls))
            .containsOne("bArr[0] = -1;")
            .containsOne("bArr[1] = 0;")
            .containsOne("bArr[2] = 127;")
    }

    /** A `double[]` fill reinterprets the 8-byte element as its IEEE-754 value. */
    @Test
    fun doubleArrayFillReinterpretsBits() {
        val cls = irClass("a.Foo")
        val arr = Local(1, IrType.array(IrType.DOUBLE), name = "dArr", isParam = true)
        cls.method("m", argTypes = listOf(IrType.array(IrType.DOUBLE))) {
            body(
                FillArrayInstruction(
                    elementWidth = 8,
                    // Raw IEEE-754 bits of 1.0 / -2.0 — huge as a long, so this proves double reinterpretation.
                    elements = longArrayOf(1.0.toRawBits(), (-2.0).toRawBits()),
                    array = arr.ref(),
                ),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("dArr[0] = 1.0;")
            .containsOne("dArr[1] = -2.0;")
    }

    /** A `float[]` fill: the width-4 int bits are reinterpreted as IEEE-754 float literals, not raw ints. */
    @Test
    fun floatArrayFillReinterpretsBits() {
        val cls = irClass("a.Foo")
        val arr = Local(1, IrType.array(IrType.FLOAT), name = "fArr", isParam = true)
        cls.method("m", argTypes = listOf(IrType.array(IrType.FLOAT))) {
            body(
                FillArrayInstruction(
                    elementWidth = 4,
                    // Raw IEEE-754 int bits of 2.0f / -3.0f — nonsense as ints, so this proves float reinterpretation.
                    elements = longArrayOf(2.0f.toRawBits().toLong(), (-3.0f).toRawBits().toLong()),
                    array = arr.ref(),
                ),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("fArr[0] = 2.0f;")
            .containsOne("fArr[1] = -3.0f;")
    }

    /** A `char[]` fill: width-2 elements render as char literals, with 0xFFFF handled (signed short → '￿'). */
    @Test
    fun charArrayFillRendersCharLiterals() {
        val cls = irClass("a.Foo")
        val arr = Local(1, IrType.array(IrType.CHAR), name = "cArr", isParam = true)
        cls.method("m", argTypes = listOf(IrType.array(IrType.CHAR))) {
            body(
                FillArrayInstruction(
                    elementWidth = 2,
                    // '0', 'f', and 0xFFFF (stored signed as a short: -1) — the low 16 bits give '￿'.
                    elements = longArrayOf('0'.code.toLong(), 'f'.code.toLong(), 0xFFFF.toShort().toLong()),
                    array = arr.ref(),
                ),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("cArr[0] = '0';")
            .containsOne("cArr[1] = 'f';")
            .containsOne("cArr[2] = '\\uffff';")
    }

    /**
     * A bare `FILL_ARRAY` with no decoded payload (not a [FillArrayInstruction]) must still bail honestly
     * with a visible marker rather than silently dropping the instruction (rule 4).
     */
    @Test
    fun undecodedFillArrayStillBails() {
        val cls = irClass("a.Foo")
        val r = Local(0, IrType.array(IrType.INT))
        cls.method("m") {
            body(assign(r.ref(), Instruction(IrOpcode.FILL_ARRAY, args = listOf(reg(9, IrType.array(IrType.INT))))))
        }
        assertThatCode(generate(cls)).containsOne("/* FILL_ARRAY */")
    }
}
