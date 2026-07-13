package com.jadxmp.pipeline.decode

import com.jadxmp.input.CodeReader
import com.jadxmp.input.FillArrayDataPayload
import com.jadxmp.input.Instruction as InputInstruction
import com.jadxmp.input.Opcode
import com.jadxmp.input.SwitchPayload
import com.jadxmp.ir.attr.AttrKey
import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.FillArrayInstruction
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.SwitchInstruction
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.model.Descriptors
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Decodes one method body from the `core:input` SPI into the flat, normalized IR the CFG stage
 * consumes. **jadx: the per-opcode logic of `InsnDecoder` + `BlockSplitter`'s jump wiring.**
 *
 * Responsibilities (and only these — structuring/typing come later):
 * - map each normalized [Opcode] to a `core:ir` [Instruction]/[Operand], attaching a *hint* type to
 *   every operand (a starting bound for inference, never a claim);
 * - resolve branch/switch targets to absolute code-unit offsets;
 * - fold `move-result` into its producing invoke/filled-new-array (the "pairing" step);
 * - drop `nop`s and pseudo-payload instructions (their data is captured onto the referring insn).
 *
 * The reused input [InputInstruction] cursor is never retained — everything is read inside the visit
 * callback (per the SPI streaming contract).
 */
class MethodDecoder(
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {

    fun decode(code: CodeReader): MethodCode {
        val decoded = ArrayList<DecodedInstruction>()
        val switchPayloads = HashMap<Int, SwitchPayload>()
        val fillPayloads = HashMap<Int, FillArrayDataPayload>()
        val errors = ArrayList<String>()
        // Instruction (by offset) awaiting a following move-result, plus the result type to give it.
        var pending: Instruction? = null
        var pendingType: IrType = IrType.UNKNOWN

        code.visitInstructions { input ->
            cancellation.ensureActive()
            input.decode()
            val opcode = input.opcode
            when (opcode) {
                Opcode.NOP -> {} // dropped

                Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD -> {
                    (input.payload as? SwitchPayload)?.let { switchPayloads[input.offset] = it }
                }
                Opcode.FILL_ARRAY_DATA_PAYLOAD -> {
                    (input.payload as? FillArrayDataPayload)?.let { fillPayloads[input.offset] = it }
                }

                Opcode.MOVE_RESULT -> {
                    // Fold into the producer: name its result register with the producer's result type.
                    val producer = pending
                    if (producer != null) {
                        producer.result = RegisterOperand(input.register(0), pendingType)
                    }
                    pending = null
                }

                else -> {
                    val di = decodeOne(input, opcode, errors)
                    decoded.add(di)
                    // Track invoke/filled-new-array so a following move-result can be folded in.
                    val insn = di.insn
                    pending = when (insn.opcode) {
                        IrOpcode.INVOKE, IrOpcode.FILLED_NEW_ARRAY -> insn
                        else -> null
                    }
                    pendingType = if (pending != null) resultTypeOf(input, opcode) else IrType.UNKNOWN
                }
            }
        }

        resolveSwitches(decoded, switchPayloads)
        resolveFillArrays(decoded, fillPayloads)

        val tries = decodeTries(code)
        return MethodCode(decoded, tries, code.registerCount, errors)
    }

    // ---- per-instruction decode --------------------------------------------

    private fun decodeOne(input: InputInstruction, opcode: Opcode, errors: MutableList<String>): DecodedInstruction {
        val offset = input.offset
        fun reg(argNum: Int, type: IrType) = RegisterOperand(input.register(argNum), type)
        fun make(insn: Instruction, fallsThrough: Boolean = true, targets: IntArray = NO_TARGETS): DecodedInstruction {
            insn.offset = offset
            val di = DecodedInstruction(insn, offset)
            di.fallsThrough = fallsThrough
            di.targets = targets
            return di
        }

        return when (opcode) {
            // ---- moves ----
            Opcode.MOVE -> make(mv(IrOpcode.MOVE, reg(0, IrType.NARROW), reg(1, IrType.NARROW)))
            Opcode.MOVE_WIDE -> make(mv(IrOpcode.MOVE, reg(0, IrType.WIDE), reg(1, IrType.WIDE)))
            Opcode.MOVE_OBJECT -> make(mv(IrOpcode.MOVE, reg(0, IrType.UNKNOWN_OBJECT), reg(1, IrType.UNKNOWN_OBJECT)))
            Opcode.MOVE_EXCEPTION -> make(Instruction(IrOpcode.MOVE_EXCEPTION, reg(0, IrType.THROWABLE)))

            // ---- constants ----
            Opcode.CONST -> make(constInsn(reg(0, IrType.NARROW), input.literal, IrType.NARROW))
            Opcode.CONST_WIDE -> make(constInsn(reg(0, IrType.WIDE), input.literal, IrType.WIDE))
            Opcode.CONST_STRING -> make(ConstStringInstruction(input.indexAsString(), reg(0, IrType.STRING)))
            Opcode.CONST_CLASS ->
                make(TypeInstruction(IrOpcode.CONST_CLASS, Descriptors.parseClassType(input.indexAsType()), reg(0, IrType.CLASS)))

            // ---- returns / throw / goto ----
            Opcode.RETURN_VOID -> make(Instruction(IrOpcode.RETURN), fallsThrough = false)
            Opcode.RETURN -> make(Instruction(IrOpcode.RETURN, result = null, args = listOf(reg(0, IrType.UNKNOWN))), fallsThrough = false)
            Opcode.THROW -> make(Instruction(IrOpcode.THROW, result = null, args = listOf(reg(0, IrType.THROWABLE))), fallsThrough = false)
            Opcode.GOTO -> make(Instruction(IrOpcode.GOTO), fallsThrough = false, targets = intArrayOf(input.target))

            // ---- conditional branches ----
            Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE -> {
                val cond = twoRegCond(opcode)
                val insn = IfInstruction(cond, args = listOf(reg(0, IrType.UNKNOWN), reg(1, IrType.UNKNOWN)))
                make(insn, fallsThrough = true, targets = intArrayOf(input.target))
            }
            Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ -> {
                val cond = zeroCond(opcode)
                // The zero may be null (object) or 0 (int): a NARROW literal covers both.
                val insn = IfInstruction(cond, args = listOf(reg(0, IrType.NARROW), LiteralOperand(0, IrType.NARROW)))
                make(insn, fallsThrough = true, targets = intArrayOf(input.target))
            }

            // ---- switch (targets resolved in a second pass via the payload) ----
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                // input.target = absolute offset of the payload table (recorded for the resolve pass).
                val sw = SwitchInstruction(IntArray(0), IntArray(0), defaultTarget = -1, selector = reg(0, IrType.NARROW_INTEGRAL))
                sw[SWITCH_PAYLOAD_OFFSET] = input.target
                make(sw, fallsThrough = true, targets = NO_TARGETS)
            }

            // ---- comparisons producing -1/0/1 ----
            Opcode.CMP_LONG -> make(cmp(reg(0, IrType.INT), reg(1, IrType.LONG), reg(2, IrType.LONG)))
            Opcode.CMPL_FLOAT, Opcode.CMPG_FLOAT -> make(cmp(reg(0, IrType.INT), reg(1, IrType.FLOAT), reg(2, IrType.FLOAT)))
            Opcode.CMPL_DOUBLE, Opcode.CMPG_DOUBLE -> make(cmp(reg(0, IrType.INT), reg(1, IrType.DOUBLE), reg(2, IrType.DOUBLE)))

            // ---- monitors ----
            Opcode.MONITOR_ENTER -> make(Instruction(IrOpcode.MONITOR_ENTER, result = null, args = listOf(reg(0, IrType.UNKNOWN_OBJECT))))
            Opcode.MONITOR_EXIT -> make(Instruction(IrOpcode.MONITOR_EXIT, result = null, args = listOf(reg(0, IrType.UNKNOWN_OBJECT))))

            // ---- casts / instance-of ----
            Opcode.CHECK_CAST -> {
                val target = Descriptors.parseClassType(input.indexAsType())
                // check-cast rewrites its own register: result and arg are the same raw register.
                make(TypeInstruction(IrOpcode.CHECK_CAST, target, reg(0, target), listOf(reg(0, IrType.UNKNOWN_OBJECT))))
            }
            Opcode.INSTANCE_OF -> {
                val target = Descriptors.parseClassType(input.indexAsType())
                make(TypeInstruction(IrOpcode.INSTANCE_OF, target, reg(0, IrType.BOOLEAN), listOf(reg(1, IrType.UNKNOWN_OBJECT))))
            }

            // ---- arrays ----
            Opcode.ARRAY_LENGTH -> make(Instruction(IrOpcode.ARRAY_LENGTH, reg(0, IrType.INT), listOf(reg(1, IrType.UNKNOWN_ARRAY))))
            Opcode.NEW_INSTANCE -> {
                val t = Descriptors.parseClassType(input.indexAsType())
                make(TypeInstruction(IrOpcode.NEW_INSTANCE, t, reg(0, t)))
            }
            Opcode.NEW_ARRAY -> {
                val t = Descriptors.parseType(input.indexAsType())
                make(TypeInstruction(IrOpcode.NEW_ARRAY, t, reg(0, t), listOf(reg(1, IrType.INT))))
            }
            Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE -> {
                val t = Descriptors.parseType(input.indexAsType())
                val elem = t.arrayElement ?: IrType.UNKNOWN
                val args = ArrayList<Operand>(input.registerCount)
                for (i in 0 until input.registerCount) args.add(RegisterOperand(input.register(i), elem))
                make(TypeInstruction(IrOpcode.FILLED_NEW_ARRAY, t, result = null, args = args))
            }
            Opcode.FILL_ARRAY_DATA -> {
                val insn = Instruction(IrOpcode.FILL_ARRAY, result = null, args = listOf(reg(0, IrType.UNKNOWN_ARRAY)))
                insn[FILL_ARRAY_PAYLOAD_OFFSET] = input.target
                make(insn)
            }

            Opcode.AGET -> make(aget(reg(0, IrType.NARROW_NUMBERS), input))
            Opcode.AGET_WIDE -> make(aget(reg(0, IrType.WIDE), input))
            Opcode.AGET_OBJECT -> make(aget(reg(0, IrType.UNKNOWN_OBJECT), input))
            Opcode.AGET_BOOLEAN -> make(aget(reg(0, IrType.BOOLEAN), input))
            Opcode.AGET_BYTE -> make(aget(reg(0, IrType.BYTE), input))
            Opcode.AGET_CHAR -> make(aget(reg(0, IrType.CHAR), input))
            Opcode.AGET_SHORT -> make(aget(reg(0, IrType.SHORT), input))

            Opcode.APUT -> make(aput(reg(0, IrType.NARROW_NUMBERS), input))
            Opcode.APUT_WIDE -> make(aput(reg(0, IrType.WIDE), input))
            Opcode.APUT_OBJECT -> make(aput(reg(0, IrType.UNKNOWN_OBJECT), input))
            Opcode.APUT_BOOLEAN -> make(aput(reg(0, IrType.BOOLEAN), input))
            Opcode.APUT_BYTE -> make(aput(reg(0, IrType.BYTE), input))
            Opcode.APUT_CHAR -> make(aput(reg(0, IrType.CHAR), input))
            Opcode.APUT_SHORT -> make(aput(reg(0, IrType.SHORT), input))

            // ---- fields ----
            Opcode.IGET -> {
                val f = toFieldRef(input.indexAsField())
                make(FieldInstruction(f, isStatic = false, isPut = false, result = reg(0, f.type), args = listOf(reg(1, f.declaringType))))
            }
            Opcode.IPUT -> {
                val f = toFieldRef(input.indexAsField())
                // iput vA(value), vB(object): FieldInstruction contract is [object, value] (value LAST).
                make(FieldInstruction(f, isStatic = false, isPut = true, result = null, args = listOf(reg(1, f.declaringType), reg(0, f.type))))
            }
            Opcode.SGET -> {
                val f = toFieldRef(input.indexAsField())
                make(FieldInstruction(f, isStatic = true, isPut = false, result = reg(0, f.type)))
            }
            Opcode.SPUT -> {
                val f = toFieldRef(input.indexAsField())
                make(FieldInstruction(f, isStatic = true, isPut = true, result = null, args = listOf(reg(0, f.type))))
            }

            // ---- invokes ----
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> make(invoke(input, InvokeKind.VIRTUAL))
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> make(invoke(input, InvokeKind.SUPER))
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> make(invoke(input, InvokeKind.DIRECT))
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> make(invoke(input, InvokeKind.STATIC))
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> make(invoke(input, InvokeKind.INTERFACE))
            Opcode.INVOKE_POLYMORPHIC, Opcode.INVOKE_POLYMORPHIC_RANGE -> make(invoke(input, InvokeKind.POLYMORPHIC))
            Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> make(invokeCustom(input))

            // ---- unary ----
            Opcode.NEG_INT -> make(ArithInstruction(ArithOp.NEGATE, reg(0, IrType.INT), listOf(reg(1, IrType.INT))))
            Opcode.NEG_LONG -> make(ArithInstruction(ArithOp.NEGATE, reg(0, IrType.LONG), listOf(reg(1, IrType.LONG))))
            Opcode.NEG_FLOAT -> make(ArithInstruction(ArithOp.NEGATE, reg(0, IrType.FLOAT), listOf(reg(1, IrType.FLOAT))))
            Opcode.NEG_DOUBLE -> make(ArithInstruction(ArithOp.NEGATE, reg(0, IrType.DOUBLE), listOf(reg(1, IrType.DOUBLE))))
            Opcode.NOT_INT -> make(Instruction(IrOpcode.NOT, reg(0, IrType.INT), listOf(reg(1, IrType.INT))))
            Opcode.NOT_LONG -> make(Instruction(IrOpcode.NOT, reg(0, IrType.LONG), listOf(reg(1, IrType.LONG))))

            // ---- conversions ----
            Opcode.INT_TO_LONG -> make(cast(reg(0, IrType.LONG), reg(1, IrType.INT)))
            Opcode.INT_TO_FLOAT -> make(cast(reg(0, IrType.FLOAT), reg(1, IrType.INT)))
            Opcode.INT_TO_DOUBLE -> make(cast(reg(0, IrType.DOUBLE), reg(1, IrType.INT)))
            Opcode.INT_TO_BYTE -> make(cast(reg(0, IrType.BYTE), reg(1, IrType.INT)))
            Opcode.INT_TO_CHAR -> make(cast(reg(0, IrType.CHAR), reg(1, IrType.INT)))
            Opcode.INT_TO_SHORT -> make(cast(reg(0, IrType.SHORT), reg(1, IrType.INT)))
            Opcode.LONG_TO_INT -> make(cast(reg(0, IrType.INT), reg(1, IrType.LONG)))
            Opcode.LONG_TO_FLOAT -> make(cast(reg(0, IrType.FLOAT), reg(1, IrType.LONG)))
            Opcode.LONG_TO_DOUBLE -> make(cast(reg(0, IrType.DOUBLE), reg(1, IrType.LONG)))
            Opcode.FLOAT_TO_INT -> make(cast(reg(0, IrType.INT), reg(1, IrType.FLOAT)))
            Opcode.FLOAT_TO_LONG -> make(cast(reg(0, IrType.LONG), reg(1, IrType.FLOAT)))
            Opcode.FLOAT_TO_DOUBLE -> make(cast(reg(0, IrType.DOUBLE), reg(1, IrType.FLOAT)))
            Opcode.DOUBLE_TO_INT -> make(cast(reg(0, IrType.INT), reg(1, IrType.DOUBLE)))
            Opcode.DOUBLE_TO_LONG -> make(cast(reg(0, IrType.LONG), reg(1, IrType.DOUBLE)))
            Opcode.DOUBLE_TO_FLOAT -> make(cast(reg(0, IrType.FLOAT), reg(1, IrType.DOUBLE)))

            // ---- binary arithmetic ----
            else -> {
                val a = arith(opcode)
                if (a != null) make(a(input)) else placeholder(input, opcode, offset, errors)
            }
        }
    }

    /**
     * Emit a register-preserving placeholder for an opcode we cannot decode faithfully yet
     * (`const-method-handle`/`const-method-type`, or a truly unknown opcode). **We never silently
     * drop it** — that would leave a destination register undefined and produce garbage output that
     * still passes the no-error signal. Instead we record a diagnostic (surfaced by the CFG pass as a
     * method-level error) and keep the registers live: the known result-defining opcodes preserve their
     * destination as a definition; anything else keeps every register as a use so nothing is orphaned.
     */
    private fun placeholder(
        input: InputInstruction,
        opcode: Opcode,
        offset: Int,
        errors: MutableList<String>,
    ): DecodedInstruction {
        errors.add("unsupported opcode $opcode at offset $offset")
        val defType: IrType? = when (opcode) {
            Opcode.CONST_METHOD_HANDLE -> IrType.objectType("java.lang.invoke.MethodHandle")
            Opcode.CONST_METHOD_TYPE -> IrType.objectType("java.lang.invoke.MethodType")
            else -> null
        }
        val insn = Instruction(IrOpcode.NOP)
        if (defType != null && input.registerCount > 0) {
            insn.result = RegisterOperand(input.register(0), defType)
        } else {
            for (i in 0 until input.registerCount) {
                insn.addArg(RegisterOperand(input.register(i), IrType.UNKNOWN))
            }
        }
        insn.offset = offset
        val di = DecodedInstruction(insn, offset)
        di.fallsThrough = true
        return di
    }

    // ---- opcode-group builders ---------------------------------------------

    private fun mv(op: IrOpcode, result: RegisterOperand, arg: RegisterOperand) =
        Instruction(op, result, listOf(arg))

    private fun constInsn(result: RegisterOperand, value: Long, type: IrType) =
        Instruction(IrOpcode.CONST, result, listOf(LiteralOperand(value, type)))

    private fun cast(result: RegisterOperand, arg: RegisterOperand) =
        Instruction(IrOpcode.CAST, result, listOf(arg))

    private fun cmp(result: RegisterOperand, a: RegisterOperand, b: RegisterOperand) =
        Instruction(IrOpcode.CMP, result, listOf(a, b))

    private fun aget(result: RegisterOperand, input: InputInstruction): Instruction {
        val array = RegisterOperand(input.register(1), IrType.array(result.type))
        val index = RegisterOperand(input.register(2), IrType.INT)
        return Instruction(IrOpcode.ARRAY_GET, result, listOf(array, index))
    }

    private fun aput(value: RegisterOperand, input: InputInstruction): Instruction {
        val array = RegisterOperand(input.register(1), IrType.array(value.type))
        val index = RegisterOperand(input.register(2), IrType.INT)
        return Instruction(IrOpcode.ARRAY_PUT, result = null, args = listOf(value, array, index))
    }

    private fun invoke(input: InputInstruction, kind: InvokeKind): InvokeInstruction {
        val ref = toMethodRef(input.indexAsMethod())
        val args = ArrayList<Operand>(input.registerCount)
        var regIdx = 0
        if (kind != InvokeKind.STATIC) {
            args.add(RegisterOperand(input.register(regIdx), ref.declaringType))
            regIdx++
        }
        for (pt in ref.paramTypes) {
            args.add(RegisterOperand(input.register(regIdx), pt))
            regIdx += Descriptors.slotsOf(pt)
        }
        return InvokeInstruction(ref, kind, result = null, args = args)
    }

    /** Map an input-model method ref (descriptor strings) to the canonical `core:ir` [MethodRef]. */
    private fun toMethodRef(ref: com.jadxmp.input.MethodRef): MethodRef = MethodRef(
        declaringType = Descriptors.parseClassType(ref.declaringClassType),
        name = ref.name,
        returnType = Descriptors.parseType(ref.returnType),
        paramTypes = ref.parameterTypes.map { Descriptors.parseType(it) },
    )

    /** Map an input-model field ref (descriptor strings) to the canonical `core:ir` [FieldRef]. */
    private fun toFieldRef(ref: com.jadxmp.input.FieldRef): FieldRef = FieldRef(
        declaringType = Descriptors.parseClassType(ref.declaringClassType),
        name = ref.name,
        type = Descriptors.parseType(ref.type),
    )

    private fun invokeCustom(input: InputInstruction): Instruction {
        // No resolvable MethodRef for invoke-custom; keep the data-flow (all argument registers) with
        // best-effort object hints so nothing is lost, and let a following move-result attach a result.
        val args = ArrayList<Operand>(input.registerCount)
        for (i in 0 until input.registerCount) args.add(RegisterOperand(input.register(i), IrType.UNKNOWN))
        return Instruction(IrOpcode.INVOKE, result = null, args = args)
    }

    /** Result type produced by an invoke / filled-new-array, for the folded move-result. */
    private fun resultTypeOf(input: InputInstruction, opcode: Opcode): IrType = when (opcode) {
        Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE -> Descriptors.parseType(input.indexAsType())
        Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> IrType.UNKNOWN
        else -> Descriptors.parseType(input.indexAsMethod().returnType)
    }

    private fun arith(opcode: Opcode): ((InputInstruction) -> Instruction)? {
        val (op, type, lit) = ARITH_TABLE[opcode] ?: return null
        return { input ->
            val result = RegisterOperand(input.register(0), type)
            val args = ArrayList<Operand>(2)
            when (lit) {
                LitKind.NONE -> {
                    // 3-reg form: dst, a, b ; 2addr form (2 regs): dst is also first source.
                    if (input.registerCount >= 3) {
                        args.add(RegisterOperand(input.register(1), opndType(type)))
                        args.add(RegisterOperand(input.register(2), shiftAwareSecond(op, type)))
                    } else {
                        args.add(RegisterOperand(input.register(0), opndType(type)))
                        args.add(RegisterOperand(input.register(1), shiftAwareSecond(op, type)))
                    }
                }
                LitKind.LIT -> {
                    args.add(RegisterOperand(input.register(1), IrType.INT))
                    args.add(LiteralOperand(input.literal, IrType.INT))
                }
                LitKind.RSUB -> {
                    // result = literal - reg  =>  args [literal, reg]
                    args.add(LiteralOperand(input.literal, IrType.INT))
                    args.add(RegisterOperand(input.register(1), IrType.INT))
                }
            }
            ArithInstruction(op, result, args)
        }
    }

    private fun opndType(resultType: IrType): IrType = resultType
    private fun shiftAwareSecond(op: ArithOp, resultType: IrType): IrType =
        if (op == ArithOp.SHL || op == ArithOp.SHR || op == ArithOp.USHR) IrType.INT else resultType

    // ---- second-pass resolution --------------------------------------------

    private fun resolveSwitches(decoded: List<DecodedInstruction>, payloads: Map<Int, SwitchPayload>) {
        // default target = the offset of the instruction following the switch in program order.
        for (i in decoded.indices) {
            val di = decoded[i]
            val sw = di.insn as? SwitchInstruction ?: continue
            val payloadOffset = sw[SWITCH_PAYLOAD_OFFSET] ?: continue
            val default = decoded.getOrNull(i + 1)?.offset ?: -1
            sw.defaultTarget = default
            val payload = payloads[payloadOffset]
            if (payload == null) {
                di.targets = if (default >= 0) intArrayOf(default) else IntArray(0)
                continue
            }
            val abs = IntArray(payload.size) { sw.offset + payload.targets[it] }
            sw.keys = payload.keys.copyOf()
            sw.caseTargets = abs
            di.targets = if (default >= 0) abs + intArrayOf(default) else abs
        }
    }

    /**
     * Second pass: turn each decoded `fill-array-data` placeholder into a canonical
     * [FillArrayInstruction] carrying the decoded elements, now that the payload table (which follows
     * the referring instruction in program order) has been collected. A missing payload leaves the bare
     * `FILL_ARRAY` placeholder in place, so codegen bails honestly instead of fabricating data (rule 4).
     */
    private fun resolveFillArrays(decoded: List<DecodedInstruction>, payloads: Map<Int, FillArrayDataPayload>) {
        for (di in decoded) {
            val insn = di.insn
            if (insn.opcode != IrOpcode.FILL_ARRAY) continue
            val off = insn[FILL_ARRAY_PAYLOAD_OFFSET] ?: continue
            val payload = payloads[off] ?: continue
            // Transfer the array operand (operand 0) to the new instruction; the placeholder is discarded.
            val fill = FillArrayInstruction(
                // A 0-width (empty) payload has no elements; treat it as 1-byte like jadx (byte/boolean).
                elementWidth = if (payload.elementSize == 0) 1 else payload.elementSize,
                elements = toLongElements(payload),
                array = insn.getArg(0),
            )
            fill.offset = insn.offset
            di.insn = fill
        }
    }

    /**
     * Widen a [FillArrayDataPayload]'s typed blob into raw `Long` bit patterns, **sign-extending** each
     * element (a negative byte/short/int stays negative), matching jadx's `getLiteralArgs`. A float's
     * 4-byte / a double's 8-byte value is carried as its raw IEEE-754 bits; codegen reinterprets it once
     * the element type is known.
     */
    private fun toLongElements(payload: FillArrayDataPayload): LongArray = when (val data = payload.data) {
        is ByteArray -> LongArray(data.size) { data[it].toLong() }
        is ShortArray -> LongArray(data.size) { data[it].toLong() }
        is IntArray -> LongArray(data.size) { data[it].toLong() }
        is LongArray -> data.copyOf()
        else -> LongArray(0)
    }

    private fun decodeTries(code: CodeReader): List<DecodedTry> {
        val tries = code.tries
        if (tries.isEmpty()) return emptyList()
        val out = ArrayList<DecodedTry>(tries.size)
        for (t in tries) {
            val ch = t.catchHandler
            val handlers = ArrayList<DecodedHandler>(ch.types.size + 1)
            for (i in ch.types.indices) {
                handlers.add(DecodedHandler(Descriptors.parseClassType(ch.types[i]), ch.handlers[i]))
            }
            if (ch.catchAllHandler >= 0) {
                handlers.add(DecodedHandler(null, ch.catchAllHandler))
            }
            out.add(DecodedTry(t.startOffset, t.endOffset, handlers))
        }
        return out
    }

    private fun twoRegCond(opcode: Opcode): ConditionOp = when (opcode) {
        Opcode.IF_EQ -> ConditionOp.EQ
        Opcode.IF_NE -> ConditionOp.NE
        Opcode.IF_LT -> ConditionOp.LT
        Opcode.IF_GE -> ConditionOp.GE
        Opcode.IF_GT -> ConditionOp.GT
        Opcode.IF_LE -> ConditionOp.LE
        else -> error("not a two-reg if: $opcode")
    }

    private fun zeroCond(opcode: Opcode): ConditionOp = when (opcode) {
        Opcode.IF_EQZ -> ConditionOp.EQ
        Opcode.IF_NEZ -> ConditionOp.NE
        Opcode.IF_LTZ -> ConditionOp.LT
        Opcode.IF_GEZ -> ConditionOp.GE
        Opcode.IF_GTZ -> ConditionOp.GT
        Opcode.IF_LEZ -> ConditionOp.LE
        else -> error("not a zero if: $opcode")
    }

    private enum class LitKind { NONE, LIT, RSUB }
    private data class ArithSpec(val op: ArithOp, val type: IrType, val lit: LitKind)

    private companion object {
        val NO_TARGETS = IntArray(0)
        val SWITCH_PAYLOAD_OFFSET = AttrKey<Int>("SWITCH_PAYLOAD_OFFSET")
        val FILL_ARRAY_PAYLOAD_OFFSET = AttrKey<Int>("FILL_ARRAY_PAYLOAD_OFFSET")

        val ARITH_TABLE: Map<Opcode, ArithSpec> = buildMap {
            fun p(o: Opcode, op: ArithOp, t: IrType, lit: LitKind = LitKind.NONE) = put(o, ArithSpec(op, t, lit))
            p(Opcode.ADD_INT, ArithOp.ADD, IrType.INT); p(Opcode.SUB_INT, ArithOp.SUB, IrType.INT)
            p(Opcode.MUL_INT, ArithOp.MUL, IrType.INT); p(Opcode.DIV_INT, ArithOp.DIV, IrType.INT)
            p(Opcode.REM_INT, ArithOp.REM, IrType.INT)
            p(Opcode.AND_INT, ArithOp.AND, IrType.INT_BOOLEAN); p(Opcode.OR_INT, ArithOp.OR, IrType.INT_BOOLEAN)
            p(Opcode.XOR_INT, ArithOp.XOR, IrType.INT_BOOLEAN)
            p(Opcode.SHL_INT, ArithOp.SHL, IrType.INT); p(Opcode.SHR_INT, ArithOp.SHR, IrType.INT)
            p(Opcode.USHR_INT, ArithOp.USHR, IrType.INT)
            p(Opcode.ADD_LONG, ArithOp.ADD, IrType.LONG); p(Opcode.SUB_LONG, ArithOp.SUB, IrType.LONG)
            p(Opcode.MUL_LONG, ArithOp.MUL, IrType.LONG); p(Opcode.DIV_LONG, ArithOp.DIV, IrType.LONG)
            p(Opcode.REM_LONG, ArithOp.REM, IrType.LONG)
            p(Opcode.AND_LONG, ArithOp.AND, IrType.LONG); p(Opcode.OR_LONG, ArithOp.OR, IrType.LONG)
            p(Opcode.XOR_LONG, ArithOp.XOR, IrType.LONG)
            p(Opcode.SHL_LONG, ArithOp.SHL, IrType.LONG); p(Opcode.SHR_LONG, ArithOp.SHR, IrType.LONG)
            p(Opcode.USHR_LONG, ArithOp.USHR, IrType.LONG)
            p(Opcode.ADD_FLOAT, ArithOp.ADD, IrType.FLOAT); p(Opcode.SUB_FLOAT, ArithOp.SUB, IrType.FLOAT)
            p(Opcode.MUL_FLOAT, ArithOp.MUL, IrType.FLOAT); p(Opcode.DIV_FLOAT, ArithOp.DIV, IrType.FLOAT)
            p(Opcode.REM_FLOAT, ArithOp.REM, IrType.FLOAT)
            p(Opcode.ADD_DOUBLE, ArithOp.ADD, IrType.DOUBLE); p(Opcode.SUB_DOUBLE, ArithOp.SUB, IrType.DOUBLE)
            p(Opcode.MUL_DOUBLE, ArithOp.MUL, IrType.DOUBLE); p(Opcode.DIV_DOUBLE, ArithOp.DIV, IrType.DOUBLE)
            p(Opcode.REM_DOUBLE, ArithOp.REM, IrType.DOUBLE)
            p(Opcode.ADD_INT_LIT, ArithOp.ADD, IrType.INT, LitKind.LIT)
            p(Opcode.MUL_INT_LIT, ArithOp.MUL, IrType.INT, LitKind.LIT)
            p(Opcode.DIV_INT_LIT, ArithOp.DIV, IrType.INT, LitKind.LIT)
            p(Opcode.REM_INT_LIT, ArithOp.REM, IrType.INT, LitKind.LIT)
            p(Opcode.AND_INT_LIT, ArithOp.AND, IrType.INT, LitKind.LIT)
            p(Opcode.OR_INT_LIT, ArithOp.OR, IrType.INT, LitKind.LIT)
            p(Opcode.XOR_INT_LIT, ArithOp.XOR, IrType.INT, LitKind.LIT)
            p(Opcode.SHL_INT_LIT, ArithOp.SHL, IrType.INT, LitKind.LIT)
            p(Opcode.SHR_INT_LIT, ArithOp.SHR, IrType.INT, LitKind.LIT)
            p(Opcode.USHR_INT_LIT, ArithOp.USHR, IrType.INT, LitKind.LIT)
            p(Opcode.RSUB_INT, ArithOp.SUB, IrType.INT, LitKind.RSUB)
        }
    }
}
