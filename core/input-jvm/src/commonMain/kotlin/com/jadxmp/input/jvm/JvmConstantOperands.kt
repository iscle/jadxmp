package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException

/** Eager, bounded per-method resolution. No mutable constant-pool reader escapes in an instruction. */
internal class JvmConstantOperands(
    private val pool: JvmConstantPool,
    private val maxWork: Long,
    private val reference: (String) -> JvmFrameValue.Reference,
) {
    private val constants = mutableMapOf<Int, Constant>()
    private val types = mutableMapOf<Int, Type>()
    // Different CONSTANT_Class/String entries may alias one huge UTF8 entry. Materialize/validate
    // its semantic value once, not once per instruction, alias, or control-flow worklist revisit.
    private val classNames = mutableMapOf<Int, JvmFrameValue.Reference>()
    private val strings = mutableMapOf<Int, String>()
    private var work = 0L

    sealed interface Constant {
        val frameType: JvmFrameValue
        data class Numeric(override val frameType: JvmFrameValue, val bits: Long) : Constant
        data class StringValue(val operand: JvmIndexedOperand.StringRef) : Constant {
            override val frameType get() = STRING
        }
        data class ClassValue(val operand: JvmIndexedOperand.TypeRef) : Constant {
            override val frameType get() = CLASS
        }
    }
    data class Type(val operand: JvmIndexedOperand.TypeRef, val frameType: JvmFrameValue.Reference)

    fun constant(index: Int): Constant = constants.getOrPut(index) {
        charge(1)
        when (val entry = pool.entry(index)) {
            is JvmConstant.IntegerValue -> Constant.Numeric(JvmFrameValue.IntValue, entry.value.toLong())
            is JvmConstant.FloatBits -> Constant.Numeric(JvmFrameValue.FloatValue, entry.bits.toLong())
            is JvmConstant.LongValue -> Constant.Numeric(JvmFrameValue.LongValue, entry.value)
            is JvmConstant.DoubleBits -> Constant.Numeric(JvmFrameValue.DoubleValue, entry.bits)
            is JvmConstant.StringRef -> {
                val value = strings.getOrPut(entry.stringIndex) {
                    pool.utf8(entry.stringIndex).also { charge(it.length.toLong()) }
                }
                Constant.StringValue(JvmIndexedOperand.StringRef(index, value))
            }
            is JvmConstant.ClassRef -> {
                // Class constants existed earlier, but became loadable only in version 49 (JVMS 4.4-C).
                if (pool.majorVersion < 49) throw ByteReaderException("ldc class constant requires JVM version 49")
                Constant.ClassValue(type(index).operand)
            }
            else -> throw ByteReaderException("unsupported ldc constant kind at index $index")
        }
    }

    fun type(index: Int): Type = types.getOrPut(index) {
        charge(1)
        val entry = pool.entry(index) as? JvmConstant.ClassRef
            ?: throw ByteReaderException("expected JVM class constant at index $index")
        val frameType = classNames.getOrPut(entry.nameIndex) {
            val name = pool.className(index)
            charge(name.length.toLong() + 2)
            // An ordinary name such as I denotes the class named I, not primitive int. Only
            // array class constants already contain a field descriptor (JVMS 4.4.1).
            reference(if (name.startsWith('[')) name else "L$name;")
        }
        Type(JvmIndexedOperand.TypeRef(index, frameType.descriptor), frameType)
    }

    private fun charge(amount: Long) {
        work += amount
        if (work > maxWork) throw ByteReaderException("JVM constant operand work limit exceeded")
    }

    private companion object {
        val STRING = JvmFrameValue.Reference("Ljava/lang/String;")
        val CLASS = JvmFrameValue.Reference("Ljava/lang/Class;")
    }
}
