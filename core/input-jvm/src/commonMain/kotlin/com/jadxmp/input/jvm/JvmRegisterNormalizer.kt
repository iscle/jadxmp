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
 * Native register lowering for primitive and reference values with normal control flow. Eagerly validates before
 * returning a reader, so its frame-size getter is safe during model construction. Unsupported
 * instructions/handlers fail the method explicitly; nothing is silently translated as a NOP.
 */
internal object JvmRegisterNormalizer {
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
        private var operandWork = 0L
        private val constantOperands = JvmConstantOperands(constants, ::chargeOperandWork, ::reference)
        private val arrayOperands = JvmArrayOperands(::chargeOperandWork, ::reference)
        private val returnFrameType = if (descriptor.returnType == "V") null else valueType(descriptor.returnType)
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
            val flow = JvmControlFlow(decoded)
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
            return NormalizedCodeReader(registerCount, code.codeOffset, instructions.map { it.resolveTargets(::position) })
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
                0x01 -> constant(JvmFrameValue.NullValue, 0)
                0x00 -> Unit // The position map redirects targets to the next emitted instruction.
                in 0x02..0x08 -> constant(JvmFrameValue.IntValue, (opcode - 3).toLong())
                0x09, 0x0a -> constant(JvmFrameValue.LongValue, (opcode - 9).toLong())
                in 0x0b..0x0d -> constant(JvmFrameValue.FloatValue, (opcode - 0x0b).toFloat().toRawBits().toLong())
                0x0e, 0x0f -> constant(JvmFrameValue.DoubleValue, (opcode - 0x0e).toDouble().toRawBits())
                0x10, 0x11 -> constant(JvmFrameValue.IntValue, (raw.operand as JvmOperand.Immediate).value.toLong())
                in 0x12..0x14 -> loadConstant(opcode)
                0x19 -> loadReference((raw.operand as JvmOperand.Local).index)
                in 0x2a..0x2d -> loadReference(opcode - 0x2a)
                0x3a -> storeReference((raw.operand as JvmOperand.Local).index)
                in 0x4b..0x4e -> storeReference(opcode - 0x4b)
                in 0x15..0x18 -> load((raw.operand as JvmOperand.Local).index, TYPES[opcode - 0x15])
                in 0x1a..0x29 -> load((opcode - 0x1a) % 4, TYPES[(opcode - 0x1a) / 4])
                in 0x2e..0x35 -> {
                    val index = pop(JvmFrameValue.IntValue)
                    val (arrayType, array) = popReference()
                    val load = arrayOperands.load(arrayType, opcode)
                    // Both inputs are snapshots in the stack bank. A wide result may reuse both
                    // popped words: ARRAY_GET reads its arguments before committing the result.
                    val result = push(load.value)
                    if (arrayType == JvmFrameValue.NullValue) {
                        // The verifier has a normal frame, but execution cannot complete this
                        // instruction normally. Both inputs were evaluated before the mandated
                        // NPE; throwing this proven null avoids inventing an array component type.
                        emit(Opcode.THROW, intArrayOf(array))
                    } else {
                        emit(load.read, intArrayOf(result, array, index))
                        // baload's result is computational Int even for Boolean arrays. Make
                        // the 0/1 boundary explicit before arbitrary JVM numeric consumers.
                        if (load.read == Opcode.AGET_BOOLEAN) emit(Opcode.BOOLEAN_TO_INT, intArrayOf(result, result))
                    }
                }
                in 0x36..0x39 -> store((raw.operand as JvmOperand.Local).index, TYPES[opcode - 0x36])
                in 0x3b..0x4a -> store((opcode - 0x3b) % 4, TYPES[(opcode - 0x3b) / 4])
                in 0x4f..0x56 -> {
                    val value = if (opcode == 0x53) popReference().second else {
                        pop(if (opcode in 0x4f..0x52) TYPES[opcode - 0x4f] else JvmFrameValue.IntValue)
                    }
                    val index = pop(JvmFrameValue.IntValue)
                    val (arrayType, array) = popReference()
                    val component = arrayOperands.store(arrayType, opcode)
                    if (arrayType == JvmFrameValue.NullValue) {
                        // Operand effects already happened in JVM order; the store itself cannot
                        // complete normally. Still validate the original following bytecode frame.
                        emit(Opcode.THROW, intArrayOf(array))
                    } else {
                        // Only the popped copy is narrowed. A duplicated assignment value below
                        // these operands must keep its original computational Int unchanged.
                        when (component.write) {
                            Opcode.APUT_BYTE -> emit(Opcode.INT_TO_BYTE, intArrayOf(value, value))
                            Opcode.APUT_CHAR -> emit(Opcode.INT_TO_CHAR, intArrayOf(value, value))
                            Opcode.APUT_SHORT -> emit(Opcode.INT_TO_SHORT, intArrayOf(value, value))
                            Opcode.APUT_BOOLEAN -> {
                                emit(Opcode.AND_INT_LIT, intArrayOf(value, value), 1)
                                emit(Opcode.INT_TO_BOOLEAN, intArrayOf(value, value))
                            }
                            Opcode.APUT_OBJECT -> {
                                // aastore accepts any reference value and checks assignability at
                                // runtime, after null/bounds. Widen the proven reference array,
                                // never narrow the value (which would replace ASE with CCE).
                                emit(Opcode.REFERENCE_ARRAY_TO_OBJECT_ARRAY, intArrayOf(array, array))
                            }
                            else -> Unit
                        }
                        emit(component.write, intArrayOf(value, array, index))
                    }
                }
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
                0xa5, 0xa6 -> {
                    val rhs = popReference().second
                    val lhs = popReference().second
                    emit(if (opcode == 0xa5) Opcode.IF_EQ else Opcode.IF_NE, intArrayOf(lhs, rhs),
                        target = (raw.operand as JvmOperand.Branch).target)
                }
                0xbe -> {
                    val (type, source) = popReference()
                    // Null is a valid verifier operand and must keep its runtime NPE. An Object
                    // join alone is not evidence of an array; do not invent a narrowing cast.
                    if (type is JvmFrameValue.Reference && !type.descriptor.startsWith('[')) {
                        fail("arraylength requires an array, found ${type.descriptor}")
                    }
                    emit(Opcode.ARRAY_LENGTH, intArrayOf(push(JvmFrameValue.IntValue), source))
                }
                0xc0, 0xc1 -> {
                    val target = constantOperands.type((raw.operand as JvmOperand.Constant).index)
                    val source = popReference().second
                    if (opcode == 0xc0) {
                        val destination = push(target.frameType)
                        check(destination == source) // CHECK_CAST reads and overwrites this stack word.
                        emit(Opcode.CHECK_CAST, intArrayOf(destination), indexedOperand = target.operand)
                    } else {
                        val result = push(JvmFrameValue.IntValue)
                        emit(Opcode.INSTANCE_OF, intArrayOf(result, source), indexedOperand = target.operand)
                        emit(Opcode.BOOLEAN_TO_INT, intArrayOf(result, result))
                    }
                }
                0xc6, 0xc7 -> {
                    val value = popReference().second
                    emit(if (opcode == 0xc6) Opcode.IF_EQZ else Opcode.IF_NEZ, intArrayOf(value),
                        target = (raw.operand as JvmOperand.Branch).target)
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
                    expect(type, checkNotNull(returnFrameType))
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
                0xb0 -> {
                    val expected = returnFrameType ?: fail("reference return in void method")
                    requireReference(expected)
                    val (actual, register) = popReference()
                    if (actual != JvmFrameValue.NullValue && actual != expected &&
                        expected != JvmReferenceTypes.OBJECT) {
                        fail("reference return assignability requires unresolved hierarchy: $actual to $expected")
                    }
                    emit(Opcode.RETURN, intArrayOf(register))
                }
                0xb1 -> {
                    if (descriptor.returnType != "V") fail("void return in value method")
                    emit(Opcode.RETURN_VOID)
                }
                else -> fail("unsupported register normalization opcode 0x${opcode.toString(16)}")
            }
        }

        private fun chargeOperandWork(amount: Long) {
            operandWork += amount
            if (operandWork > limits.maxConstantWork) fail("JVM constant operand work limit exceeded")
        }

        private fun loadConstant(opcode: Int) {
            val index = (raw.operand as JvmOperand.Constant).index
            val constant = constantOperands.constant(index)
            if ((opcode == 0x14) != (constant.frameType.words == 2)) fail("ldc constant has the wrong category")
            when (constant) {
                is JvmConstantOperands.Constant.Numeric -> constant(constant.frameType, constant.bits)
                is JvmConstantOperands.Constant.StringValue -> emit(Opcode.CONST_STRING,
                    intArrayOf(push(constant.frameType)), indexedOperand = constant.operand)
                is JvmConstantOperands.Constant.ClassValue -> emit(Opcode.CONST_CLASS,
                    intArrayOf(push(constant.frameType)), indexedOperand = constant.operand)
            }
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

        private fun requireReference(value: JvmFrameValue) {
            if (value !is JvmFrameValue.Reference && value != JvmFrameValue.NullValue) {
                fail("expected initialized reference but found $value")
            }
        }

        private fun loadReference(index: Int) {
            val value = frame.local(index)
            requireReference(value)
            emit(Opcode.MOVE_OBJECT, intArrayOf(push(value), localRegister(index)))
        }

        private fun storeReference(index: Int) {
            val (value, register) = popReference()
            frame.store(index, value)
            emit(Opcode.MOVE_OBJECT, intArrayOf(localRegister(index), register))
        }

        private fun popReference(): Pair<JvmFrameValue, Int> {
            val value = frame.pop()
            requireReference(value)
            return value to (stackBase + frame.stackWords)
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
            target: Int = -1, payload: InstructionPayload? = null, indexedOperand: JvmIndexedOperand? = null,
        ) {
            if (!emitting) return
            instructions += JvmNormalizedInstruction(instructions.size, code.codeOffset + raw.offset,
                opcode, registers, literal, if (raw.wide) 0xc4 else raw.opcode,
                JvmOpcodeNames.name(raw), target, payload, indexedOperand)
        }

        private fun move(type: JvmFrameValue) = when {
            type.words == 2 -> Opcode.MOVE_WIDE
            type is JvmFrameValue.Reference || type == JvmFrameValue.NullValue -> Opcode.MOVE_OBJECT
            else -> Opcode.MOVE
        }
        private fun fail(message: String): Nothing = throw ByteReaderException(message)
    }

    private class NormalizedCodeReader(
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
