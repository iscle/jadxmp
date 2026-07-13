package com.jadxmp.pipeline.ssa

import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.model.Descriptors

/**
 * Maps a method's incoming arguments to their DEX register numbers.
 *
 * DEX places the incoming arguments in the **last** `ins` registers of the frame, `this` first (for
 * instance methods), then each declared parameter — with `long`/`double` occupying two consecutive
 * registers. This reconstructs that layout from the method signature and the frame's register count,
 * which SSA needs to create the parameter definitions at method entry.
 */
class MethodParams private constructor(
    /** Register number of `this`, or -1 for a static method. */
    val thisReg: Int,
    /** Register number of each declared parameter, in order (wide params still occupy one entry here). */
    val paramRegs: IntArray,
) {
    companion object {
        fun of(method: IrMethod, registerCount: Int): MethodParams {
            var insSize = if (method.isStatic) 0 else 1
            for (t in method.argTypes) insSize += Descriptors.slotsOf(t)
            val base = registerCount - insSize
            if (base < 0) {
                // Malformed frame; degrade to "no reconstructable params" rather than crash.
                return MethodParams(-1, IntArray(0))
            }
            var cursor = base
            val thisReg: Int
            if (method.isStatic) {
                thisReg = -1
            } else {
                thisReg = cursor
                cursor += 1
            }
            val regs = IntArray(method.argTypes.size)
            for (i in method.argTypes.indices) {
                regs[i] = cursor
                cursor += Descriptors.slotsOf(method.argTypes[i])
            }
            return MethodParams(thisReg, regs)
        }
    }

    /** Type of `this` (the declaring class), used to seed its definition. */
    fun thisType(method: IrMethod): IrType = IrType.objectType(method.declaringClass.fullName)
}
