package com.jadxmp.input.jvm

import com.jadxmp.input.CodeReader
import com.jadxmp.input.DebugInfo
import com.jadxmp.input.Instruction
import com.jadxmp.input.InlineSwitchPayload
import com.jadxmp.input.InstructionPayload
import com.jadxmp.input.Opcode
import com.jadxmp.input.TryBlock
import com.jadxmp.io.ByteReaderException

/**
 * Native lowering slice for primitive methods with normal control flow. Eagerly validates before
 * returning a reader, so its frame-size getter is safe during model construction. Unsupported
 * instructions/handlers fail the method explicitly; nothing is silently translated as a NOP.
 */
internal object JvmPrimitiveNormalizer {
    fun normalize(
        owner: String,
        member: JvmMember,
        descriptor: JvmMethodDescriptor,
        constants: JvmConstantPool,
        code: JvmCodeAttribute,
        limits: JvmAnalysisLimits = JvmAnalysisLimits(),
    ): CodeReader = Lowering(owner, member, descriptor, constants, code, limits).run()

    private class Lowering(
        private val owner: String,
        private val member: JvmMember,
        private val descriptor: JvmMethodDescriptor,
        private val constants: JvmConstantPool,
        private val code: JvmCodeAttribute,
        private val limits: JvmAnalysisLimits,
    ) {
        private val isStatic = member.accessFlags and 8 != 0
        private val incomingWords = descriptor.argumentSlots + if (isStatic) 0 else 1
        private val nonParameterWords = code.maxLocals - incomingWords
        // JVM stores may overwrite parameter slots with wide values spanning into later locals.
        // Keep all original locals contiguous; seed them from a separate high incoming bank once.
        private val stackBase = code.maxLocals
        private val scratchBase = stackBase + code.maxStack
        private val parameterBase = scratchBase + 4
        private val registerCount = parameterBase + incomingWords
        private var frame = JvmFrame(code.maxLocals, code.maxStack)
        private val referenceTypes = mutableMapOf<String, JvmFrameValue.Reference>()
        private val numericConstants = mutableMapOf<Int, Constant>()
        private val instructions = mutableListOf<JvmNormalizedInstruction>()
        private var emitting = true
        private lateinit var raw: JvmInstruction

        fun run(): CodeReader {
            if (descriptor.text != member.descriptor) fail("parsed descriptor does not match method")
            if (member.name == "<init>") fail("constructor lowering is not supported yet")
            if (incomingWords > 255 || nonParameterWords < 0) fail("parameters exceed the JVM local frame")
            if (code.handlers.isNotEmpty()) fail("exception-handler lowering is not supported yet")
            val decoded = code.decodeInstructions()
            raw = decoded.first()
            var local = 0
            if (!isStatic) {
                JvmDescriptors.className(owner, allowArray = false)
                seedParameter(local++, reference("L$owner;"))
            }
            for (type in descriptor.parameterTypes) {
                val value = valueType(type)
                seedParameter(local, value)
                local += value.words
            }
            val flow = JvmPrimitiveFlow(decoded)
            emitting = false
            val incoming = flow.analyze(frame, limits, ::transfer)
            emitting = true
            val positions = mutableMapOf<Int, Int>()
            flow.emit(incoming) { working, instruction ->
                // Entry copies have already been emitted. Even raw PC zero jumps into the body.
                // A removed NOP/pop maps to the next emitted instruction, not an absent ordinal.
                positions[instruction.offset] = instructions.size
                transfer(working, instruction)
            }
            fun position(offset: Int): Int {
                val result = positions[offset] ?: fail("missing branch target at bytecode $offset")
                if (result >= instructions.size) fail("branch target has no emitted instruction at bytecode $offset")
                return result
            }
            return PrimitiveCodeReader(registerCount, code.codeOffset, instructions.map { it.resolveTargets(::position) })
        }

        private fun transfer(working: JvmFrame, instruction: JvmInstruction) {
            frame = working
            raw = instruction
            try {
                lower()
            } catch (error: ByteReaderException) {
                throw ByteReaderException("JVM ${member.name}${member.descriptor} at ${raw.offset}: ${error.message}")
            }
        }

        private fun lower() {
            when (val opcode = raw.opcode) {
                0x00 -> Unit // The position map redirects targets to the next emitted instruction.
                in 0x02..0x08 -> constant(JvmFrameValue.IntValue, (opcode - 3).toLong())
                0x09, 0x0a -> constant(JvmFrameValue.LongValue, (opcode - 9).toLong())
                in 0x0b..0x0d -> constant(JvmFrameValue.FloatValue, (opcode - 0x0b).toFloat().toRawBits().toLong())
                0x0e, 0x0f -> constant(JvmFrameValue.DoubleValue, (opcode - 0x0e).toDouble().toRawBits())
                0x10, 0x11 -> constant(JvmFrameValue.IntValue, (raw.operand as JvmOperand.Immediate).value.toLong())
                in 0x12..0x14 -> loadConstant(opcode)
                in 0x15..0x18 -> load((raw.operand as JvmOperand.Local).index, TYPES[opcode - 0x15])
                in 0x1a..0x29 -> load((opcode - 0x1a) % 4, TYPES[(opcode - 0x1a) / 4])
                in 0x36..0x39 -> store((raw.operand as JvmOperand.Local).index, TYPES[opcode - 0x36])
                in 0x3b..0x4a -> store((opcode - 0x3b) % 4, TYPES[(opcode - 0x3b) / 4])
                in 0x57..0x5f -> permutation(STACK_OPERATIONS[opcode - 0x57])
                in 0x60..0x73 -> {
                    val type = TYPES[(opcode - 0x60) % 4]
                    binary(ARITHMETIC[(opcode - 0x60) / 4][(opcode - 0x60) % 4], type, type, type)
                }
                in 0x74..0x77 -> unary(NEGATE[opcode - 0x74], TYPES[opcode - 0x74], TYPES[opcode - 0x74])
                in 0x78..0x7d -> {
                    val type = TYPES[(opcode - 0x78) % 2]
                    binary(SHIFTS[(opcode - 0x78) / 2][(opcode - 0x78) % 2], type, JvmFrameValue.IntValue, type)
                }
                in 0x7e..0x83 -> {
                    val type = TYPES[(opcode - 0x7e) % 2]
                    binary(BITWISE[(opcode - 0x7e) / 2][(opcode - 0x7e) % 2], type, type, type)
                }
                0x84 -> {
                    val increment = raw.operand as JvmOperand.Increment
                    expect(frame.local(increment.index), JvmFrameValue.IntValue)
                    val register = localRegister(increment.index)
                    emit(Opcode.ADD_INT_LIT, intArrayOf(register, register), increment.amount.toLong())
                }
                in 0x85..0x93 -> CONVERSIONS[opcode - 0x85].let { unary(it.opcode, it.from, it.to) }
                0x94 -> binary(Opcode.CMP_LONG, JvmFrameValue.LongValue, JvmFrameValue.LongValue, JvmFrameValue.IntValue)
                0x95, 0x96 -> binary(if (opcode == 0x95) Opcode.CMPL_FLOAT else Opcode.CMPG_FLOAT,
                    JvmFrameValue.FloatValue, JvmFrameValue.FloatValue, JvmFrameValue.IntValue)
                0x97, 0x98 -> binary(if (opcode == 0x97) Opcode.CMPL_DOUBLE else Opcode.CMPG_DOUBLE,
                    JvmFrameValue.DoubleValue, JvmFrameValue.DoubleValue, JvmFrameValue.IntValue)
                in 0x99..0x9e -> {
                    val value = pop(JvmFrameValue.IntValue)
                    emit(ZERO_BRANCHES[opcode - 0x99], intArrayOf(value), target = (raw.operand as JvmOperand.Branch).target)
                }
                in 0x9f..0xa4 -> {
                    val rhs = pop(JvmFrameValue.IntValue)
                    val lhs = pop(JvmFrameValue.IntValue)
                    emit(INTEGER_BRANCHES[opcode - 0x9f], intArrayOf(lhs, rhs), target = (raw.operand as JvmOperand.Branch).target)
                }
                0xa7, 0xc8 -> emit(Opcode.GOTO, target = (raw.operand as JvmOperand.Branch).target)
                0xaa, 0xab -> {
                    val key = pop(JvmFrameValue.IntValue)
                    if (emitting) {
                        val table = raw.operand as JvmOperand.Switch
                        emit(Opcode.SWITCH, intArrayOf(key), payload = InlineSwitchPayload(
                            table.cases.map { it.key }.toIntArray(), table.cases.map { it.target }.toIntArray(), table.defaultTarget))
                    }
                }
                in 0xac..0xaf -> {
                    val type = TYPES[opcode - 0xac]
                    if (descriptor.returnType == "V") fail("value return in void method")
                    expect(type, valueType(descriptor.returnType))
                    val result = pop(type)
                    // JVMS ireturn performs narrowing itself, even without a preceding i2b/i2c/i2s.
                    // Boolean return uses the low bit, not the language-level nonzero truth test.
                    when (descriptor.returnType) {
                        "B" -> emit(Opcode.INT_TO_BYTE, intArrayOf(result, result))
                        "C" -> emit(Opcode.INT_TO_CHAR, intArrayOf(result, result))
                        "S" -> emit(Opcode.INT_TO_SHORT, intArrayOf(result, result))
                        "Z" -> emit(Opcode.AND_INT_LIT, intArrayOf(result, result), 1)
                    }
                    emit(Opcode.RETURN, intArrayOf(result))
                }
                0xb1 -> {
                    if (descriptor.returnType != "V") fail("void return in value method")
                    emit(Opcode.RETURN_VOID)
                }
                else -> fail("unsupported primitive normalization opcode 0x${opcode.toString(16)}")
            }
        }

        private fun loadConstant(opcode: Int) {
            val index = (raw.operand as JvmOperand.Constant).index
            val constant = numericConstants.getOrPut(index) {
                when (val value = constants.entry(index)) {
                    is JvmConstant.IntegerValue -> Constant(JvmFrameValue.IntValue, value.value.toLong())
                    is JvmConstant.FloatBits -> Constant(JvmFrameValue.FloatValue, value.bits.toLong())
                    is JvmConstant.LongValue -> Constant(JvmFrameValue.LongValue, value.value)
                    is JvmConstant.DoubleBits -> Constant(JvmFrameValue.DoubleValue, value.bits)
                    else -> fail("non-numeric ldc requires reference or dynamic-constant lowering")
                }
            }
            if ((opcode == 0x14) != (constant.type.words == 2)) fail("ldc constant has the wrong category")
            constant(constant.type, constant.bits)
        }

        private fun constant(type: JvmFrameValue, bits: Long) {
            val destination = push(type)
            emit(if (type.words == 2) Opcode.CONST_WIDE else Opcode.CONST, intArrayOf(destination), bits)
        }

        private fun load(index: Int, type: JvmFrameValue) {
            expect(frame.local(index), type)
            // Materialize now: later stores/iinc must not change values already on the operand stack.
            emit(move(type), intArrayOf(push(type), localRegister(index)))
        }

        private fun store(index: Int, type: JvmFrameValue) {
            val source = pop(type)
            frame.store(index, type)
            emit(move(type), intArrayOf(localRegister(index), source))
        }

        private fun unary(opcode: Opcode, from: JvmFrameValue, to: JvmFrameValue) {
            val source = pop(from)
            emit(opcode, intArrayOf(push(to), source))
        }

        private fun binary(opcode: Opcode, left: JvmFrameValue, right: JvmFrameValue, result: JvmFrameValue) {
            val rhs = pop(right)
            val lhs = pop(left)
            emit(opcode, intArrayOf(push(result), lhs, rhs))
        }

        private fun permutation(operation: JvmStackOperation) {
            val change = frame.apply(operation)
            if (change.output.isEmpty()) return
            var word = 0
            val sources = IntArray(change.inputs.size)
            for ((index, type) in change.inputs.withIndex()) {
                sources[index] = scratchBase + word
                emit(move(type), intArrayOf(sources[index], stackBase + change.startWord + word))
                word += type.words
            }
            var destination = stackBase + change.startWord
            for (index in change.output) {
                val type = change.inputs[index]
                emit(move(type), intArrayOf(destination, sources[index]))
                destination += type.words
            }
        }

        private fun pop(expected: JvmFrameValue): Int {
            val actual = frame.pop()
            expect(actual, expected)
            return stackBase + frame.stackWords
        }

        private fun push(value: JvmFrameValue): Int {
            val result = stackBase + frame.stackWords
            frame.push(value)
            return result
        }

        private fun seedParameter(index: Int, value: JvmFrameValue) {
            frame.store(index, value)
            emit(move(value), intArrayOf(index, parameterBase + index))
        }

        private fun localRegister(index: Int): Int = index

        private fun valueType(descriptor: String): JvmFrameValue = when (descriptor) {
            "Z", "B", "C", "S", "I" -> JvmFrameValue.IntValue
            "J" -> JvmFrameValue.LongValue
            "F" -> JvmFrameValue.FloatValue
            "D" -> JvmFrameValue.DoubleValue
            else -> reference(descriptor)
        }

        private fun reference(descriptor: String) = referenceTypes.getOrPut(descriptor) { JvmFrameValue.Reference(descriptor) }
        private fun expect(actual: JvmFrameValue, expected: JvmFrameValue) {
            if (actual != expected) fail("expected $expected but found $actual")
        }

        private fun emit(opcode: Opcode, registers: IntArray = intArrayOf(), literal: Long = 0,
            target: Int = -1, payload: InstructionPayload? = null,
        ) {
            if (!emitting) return
            instructions += JvmNormalizedInstruction(instructions.size, code.codeOffset + raw.offset,
                opcode, registers, literal, if (raw.wide) 0xc4 else raw.opcode,
                JvmOpcodeNames.name(raw), target, payload)
        }

        private fun move(type: JvmFrameValue) = when {
            type.words == 2 -> Opcode.MOVE_WIDE
            type is JvmFrameValue.Reference -> Opcode.MOVE_OBJECT
            else -> Opcode.MOVE
        }
        private fun fail(message: String): Nothing = throw ByteReaderException(message)
    }

    private class PrimitiveCodeReader(
        override val registerCount: Int,
        override val codeOffset: Int,
        private val instructions: List<Instruction>,
    ) : CodeReader {
        override val unitsCount: Int get() = instructions.size
        override val tries: List<TryBlock> get() = emptyList()
        override val debugInfo: DebugInfo? get() = null
        override fun visitInstructions(visitor: (Instruction) -> Unit) = instructions.forEach(visitor)
    }

    private val ZERO_BRANCHES = listOf(Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ)
    private val INTEGER_BRANCHES = listOf(Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE)

    private data class Constant(val type: JvmFrameValue, val bits: Long)
    private data class Conversion(val opcode: Opcode, val from: JvmFrameValue, val to: JvmFrameValue)
    private val TYPES = listOf(JvmFrameValue.IntValue, JvmFrameValue.LongValue, JvmFrameValue.FloatValue, JvmFrameValue.DoubleValue)
    private val STACK_OPERATIONS = listOf(JvmStackOperation.POP, JvmStackOperation.POP2, JvmStackOperation.DUP,
        JvmStackOperation.DUP_X1, JvmStackOperation.DUP_X2, JvmStackOperation.DUP2, JvmStackOperation.DUP2_X1,
        JvmStackOperation.DUP2_X2, JvmStackOperation.SWAP)
    private val ARITHMETIC = listOf("ADD", "SUB", "MUL", "DIV", "REM").map { operation ->
        listOf("INT", "LONG", "FLOAT", "DOUBLE").map { Opcode.valueOf("${operation}_$it") }
    }
    private val NEGATE = listOf(Opcode.NEG_INT, Opcode.NEG_LONG, Opcode.NEG_FLOAT, Opcode.NEG_DOUBLE)
    private val SHIFTS = listOf(listOf(Opcode.SHL_INT, Opcode.SHL_LONG), listOf(Opcode.SHR_INT, Opcode.SHR_LONG),
        listOf(Opcode.USHR_INT, Opcode.USHR_LONG))
    private val BITWISE = listOf(listOf(Opcode.AND_INT, Opcode.AND_LONG), listOf(Opcode.OR_INT, Opcode.OR_LONG),
        listOf(Opcode.XOR_INT, Opcode.XOR_LONG))
    private val CONVERSIONS = listOf(
        Conversion(Opcode.INT_TO_LONG, TYPES[0], TYPES[1]), Conversion(Opcode.INT_TO_FLOAT, TYPES[0], TYPES[2]),
        Conversion(Opcode.INT_TO_DOUBLE, TYPES[0], TYPES[3]), Conversion(Opcode.LONG_TO_INT, TYPES[1], TYPES[0]),
        Conversion(Opcode.LONG_TO_FLOAT, TYPES[1], TYPES[2]), Conversion(Opcode.LONG_TO_DOUBLE, TYPES[1], TYPES[3]),
        Conversion(Opcode.FLOAT_TO_INT, TYPES[2], TYPES[0]), Conversion(Opcode.FLOAT_TO_LONG, TYPES[2], TYPES[1]),
        Conversion(Opcode.FLOAT_TO_DOUBLE, TYPES[2], TYPES[3]), Conversion(Opcode.DOUBLE_TO_INT, TYPES[3], TYPES[0]),
        Conversion(Opcode.DOUBLE_TO_LONG, TYPES[3], TYPES[1]), Conversion(Opcode.DOUBLE_TO_FLOAT, TYPES[3], TYPES[2]),
        Conversion(Opcode.INT_TO_BYTE, TYPES[0], TYPES[0]), Conversion(Opcode.INT_TO_CHAR, TYPES[0], TYPES[0]),
        Conversion(Opcode.INT_TO_SHORT, TYPES[0], TYPES[0]),
    )
}
