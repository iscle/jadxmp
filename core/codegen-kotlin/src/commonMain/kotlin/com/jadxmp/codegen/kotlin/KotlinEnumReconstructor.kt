package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType

/**
 * Recovers a desugared `ACC_ENUM` class into Kotlin `enum class` shape — hiding the synthetic
 * `$VALUES`/`values()`/`valueOf(String)` and **suppressing** the `<clinit>` enum-construction so the
 * companion `init {}` never reassigns the entry `val`s ("val cannot be reassigned").
 * **jadx: EnumVisitor (design oracle)** — re-derived independently for the Kotlin backend (the Java
 * backend's [com.jadxmp.codegen.java] `EnumReconstruction` is the design template, not a source).
 *
 * ### Deliberately conservative scope (CLAUDE rule 4 — over-conservative beats wrong)
 * [analyze] returns non-null **only** when every declared `<init>` is a *default* enum constructor
 * (no real parameters ⇒ argument-less entries `A, B, C`). That is the case where dropping the
 * `<clinit>` construction and emitting bare Kotlin enum entries is provably correct and always
 * recompiles. An enum whose constructor takes real arguments would need a reconstructed Kotlin primary
 * constructor + instance-field initialization (coupled to instance-field-init reconstruction, which is
 * a separate concern) to render `A(args)` faithfully; until that exists, such an enum is left to the
 * ordinary class path (no regression) rather than emitting bare entries that cannot call the
 * argument-taking constructor.
 */
internal class KotlinEnumReconstruction private constructor(
    /** Fields hidden from the declaration: the synthetic `$VALUES` backing array. */
    val hiddenFields: Set<IrField>,
    /** Compiler-synthesized methods hidden: `values()` clone, `valueOf(String)`, the `$values()` builder. */
    val hiddenMethods: Set<IrMethod>,
    /** The `<clinit>` instructions (enum construction + `$VALUES` build + dead code) to not render. */
    val suppressedClinitInsns: Set<Instruction>,
) {
    companion object {
        private const val ENUM_CLASS = "java.lang.Enum"

        /** The number of synthetic leading `<init>` params (`name`, then `ordinal`). */
        private fun syntheticArgCount(paramOrArgCount: Int): Int = if (paramOrArgCount >= 2) 2 else paramOrArgCount

        fun analyze(cls: IrClass): KotlinEnumReconstruction? {
            if (!KotlinModifiers.has(cls.accessFlags, KotlinModifiers.ENUM)) return null
            if ((cls.superType as? IrType.Object)?.className != ENUM_CLASS) return null

            val clsType = IrType.objectType(cls.fullName)
            val valuesField = findValuesField(cls, clsType) ?: return null

            val enumConstantFields = cls.fields.filter { isEnumConstant(it, clsType) }.toSet()

            val clinit = cls.methods.firstOrNull { it.name == "<clinit>" }
            // Constant fields require a <clinit> to recover/suppress their construction; without it we
            // cannot prove the construction is gone, so bail.
            if (enumConstantFields.isNotEmpty() && clinit == null) return null

            // Conservative gate: every constructor must be a default (argument-less) enum constructor, so
            // the entries render as bare `A, B, C` with nothing to reconstruct.
            val constructors = cls.methods.filter { it.name == "<init>" }
            if (!constructors.all { isDefaultEnumConstructor(it) }) return null

            val statements = clinit?.let { flatten(it) } ?: emptyList()

            // Every enum-constant store must be backed by a DIRECT `new E(name, ordinal)` construction of
            // the enum type itself. An anonymous-body entry (`A { override … }`) is desugared to
            // `A = new E$1("A", 0)` — a subclass whose construction we neither suppress (it isn't a
            // `new E`) nor whose override body we can render as a bare entry, so we'd drop the body AND
            // leave a dangling `E$1(…)` in the companion init. Bail to the ordinary path instead (rule 4).
            if (!allConstantsAreDirectConstructions(statements, cls, enumConstantFields)) return null

            val suppressed = if (clinit != null) {
                computeSuppressed(statements, cls, clsType, enumConstantFields, valuesField)
            } else {
                emptySet()
            }

            // A residual (kept) statement must not read a value whose def we suppressed — that would
            // dangle. For a default-ctor enum the residual is normally empty; bail otherwise (rule 4).
            if (!residualIsClean(statements, suppressed)) return null

            val hiddenMethods = classifySyntheticMethods(cls, clsType, valuesField)
            return KotlinEnumReconstruction(setOf(valuesField), hiddenMethods, suppressed)
        }

        /**
         * A synthetic-only enum constructor (no real params) whose body is just the `super(name, ordinal)`
         * call and the terminal return — so the reconstructed `enum` needs no explicit constructor.
         */
        fun isDefaultEnumConstructor(ctor: IrMethod): Boolean {
            if (ctor.argTypes.size > syntheticArgCount(ctor.argTypes.size)) return false
            val superCall = flatten(ctor).firstOrNull { isEnumSuperCall(it) }
            for (stmt in flatten(ctor)) {
                if (stmt === superCall) continue
                when (stmt.opcode) {
                    IrOpcode.NOP, IrOpcode.GOTO, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
                    IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT,
                    -> continue
                    IrOpcode.RETURN -> if (stmt.argCount == 0) continue else return false
                    else -> return false
                }
            }
            return true
        }

        private fun isEnumSuperCall(insn: Instruction): Boolean {
            val inv = insn as? InvokeInstruction ?: return false
            if (!inv.methodRef.isConstructor) return false
            return (inv.methodRef.declaringType as? IrType.Object)?.className == ENUM_CLASS
        }

        /** Whether [method]'s residual (non-suppressed) `<clinit>` statements are all no-ops. */
        fun residualIsEmpty(method: IrMethod, suppressed: Set<Instruction>): Boolean {
            for (stmt in flatten(method)) {
                if (stmt in suppressed) continue
                if (stmt.contains(AttrFlag.DONT_GENERATE)) continue
                when (stmt.opcode) {
                    IrOpcode.NOP, IrOpcode.GOTO, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
                    IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT,
                    -> continue
                    IrOpcode.RETURN -> if (stmt.argCount == 0) continue else return false
                    else -> return false
                }
            }
            return true
        }

        // ---- field / method classification ----

        private fun findValuesField(cls: IrClass, clsType: IrType): IrField? {
            val candidates = cls.fields.filter { f ->
                KotlinModifiers.has(f.accessFlags, KotlinModifiers.STATIC) &&
                    f.type is IrType.ArrayType &&
                    (f.type as IrType.ArrayType).arrayRootElement == clsType
            }
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) return candidates[0]
            return candidates.firstOrNull { it.name == "\$VALUES" }
                ?: candidates.firstOrNull { it.contains(AttrFlag.SYNTHETIC) }
        }

        private fun isEnumConstant(field: IrField, clsType: IrType): Boolean =
            KotlinModifiers.has(field.accessFlags, KotlinModifiers.ENUM) &&
                KotlinModifiers.has(field.accessFlags, KotlinModifiers.STATIC) &&
                KotlinModifiers.has(field.accessFlags, KotlinModifiers.FINAL) &&
                field.type == clsType

        /**
         * The compiler-synthesized helpers to hide: the canonical `values()` / `valueOf(String)` (a Kotlin
         * `enum class` regenerates them) and the `$values()` array builder. Matched by exact canonical
         * signature so a user overload (`valueOf(Int)`) is never dropped. Obfuscated (renamed) synthetics
         * are NOT recognised here — such enums fail the default-ctor gate or the residual check and fall
         * back to the ordinary path rather than risk hiding a user member (rule 4).
         */
        private fun classifySyntheticMethods(cls: IrClass, clsType: IrType, valuesField: IrField): Set<IrMethod> {
            val enumArray = IrType.array(clsType)
            val hidden = HashSet<IrMethod>()
            for (m in cls.methods) {
                val isValues = m.name == "values" && m.argTypes.isEmpty() && m.returnType == enumArray
                val isValueOf = m.name == "valueOf" && m.argTypes.size == 1 &&
                    (m.argTypes[0] as? IrType.Object)?.className == "java.lang.String" &&
                    m.returnType == clsType
                val isBuilder = m.name == "\$values" && m.argTypes.isEmpty() && m.returnType == enumArray &&
                    KotlinModifiers.has(m.accessFlags, KotlinModifiers.STATIC)
                if (isValues || isValueOf || isBuilder) hidden.add(m)
            }
            return hidden
        }

        // ---- suppression of enum-construction in <clinit> ----

        private fun computeSuppressed(
            statements: List<Instruction>,
            cls: IrClass,
            clsType: IrType,
            enumConstantFields: Set<IrField>,
            valuesField: IrField,
        ): Set<Instruction> {
            val suppressed = HashSet<Instruction>()
            // Every enum-type construction (top-level or inlined into a store/array).
            suppressed.addAll(collectEnumConstructors(statements, cls.fullName))
            // The STATIC_PUTs into enum-constant fields.
            val constNames = enumConstantFields.map { it.name }.toSet()
            for (stmt in statements) {
                if (stmt.opcode != IrOpcode.STATIC_PUT) continue
                val fr = (stmt as? FieldInstruction)?.fieldRef ?: continue
                if ((fr.declaringType as? IrType.Object)?.className != cls.fullName) continue
                if (fr.name in constNames) suppressed.add(stmt)
            }

            // The `$VALUES = <array>` store and the array it builds (+ its element puts).
            val valuesStore = statements.firstOrNull { stmt ->
                stmt.opcode == IrOpcode.STATIC_PUT &&
                    (stmt as? FieldInstruction)?.fieldRef?.name == valuesField.name
            }
            if (valuesStore != null) {
                suppressed.add(valuesStore)
                val producer = producerOf(valuesStore.getArg(valuesStore.argCount - 1))
                if (producer != null) {
                    suppressed.add(producer)
                    val arrayVar = producer.result?.ssaValue
                    if (arrayVar != null) {
                        for (stmt in statements) {
                            if (stmt.opcode == IrOpcode.ARRAY_PUT && stmt.argCount >= 2 &&
                                (stmt.getArg(1) as? RegisterOperand)?.ssaValue == arrayVar
                            ) {
                                suppressed.add(stmt)
                            }
                        }
                    }
                }
            }

            // Dead-code fixpoint: a pure producer whose result no surviving statement reads is dead.
            val reads = statements.associateWith { readSsaValues(it) }
            var changed = true
            while (changed) {
                changed = false
                for (stmt in statements) {
                    if (stmt in suppressed) continue
                    if (!isPureProducer(stmt)) continue
                    val v = stmt.result?.ssaValue ?: continue
                    val liveReader = statements.any { it !in suppressed && it !== stmt && v in (reads[it] ?: emptySet()) }
                    if (!liveReader) {
                        suppressed.add(stmt)
                        changed = true
                    }
                }
            }
            return suppressed
        }

        private fun collectEnumConstructors(statements: List<Instruction>, enumClassName: String): List<Instruction> {
            val out = ArrayList<Instruction>()
            fun visit(insn: Instruction) {
                if (isEnumConstructor(insn, enumClassName)) out.add(insn)
                for (i in 0 until insn.argCount) {
                    (insn.getArg(i) as? InstructionOperand)?.let { visit(it.instruction) }
                }
            }
            for (stmt in statements) visit(stmt)
            return out
        }

        /**
         * True iff every `<clinit>` `STATIC_PUT` into an enum-constant field is fed by a direct
         * `new E(…)` construction of the enum type. A store traced to a subclass construction
         * (`new E$1(…)`, an anonymous-body entry) or to no traceable construction returns false.
         */
        private fun allConstantsAreDirectConstructions(
            statements: List<Instruction>,
            cls: IrClass,
            enumConstantFields: Set<IrField>,
        ): Boolean {
            val constNames = enumConstantFields.map { it.name }.toSet()
            for (stmt in statements) {
                if (stmt.opcode != IrOpcode.STATIC_PUT) continue
                val fr = (stmt as? FieldInstruction)?.fieldRef ?: continue
                if ((fr.declaringType as? IrType.Object)?.className != cls.fullName) continue
                if (fr.name !in constNames) continue
                val producer = producerOf(stmt.getArg(stmt.argCount - 1)) ?: return false
                if (!isEnumConstructor(producer, cls.fullName)) return false
            }
            return true
        }

        private fun isEnumConstructor(insn: Instruction, enumClassName: String): Boolean {
            val inv = insn as? InvokeInstruction ?: return false
            if (inv.opcode != IrOpcode.CONSTRUCTOR) return false
            return (inv.methodRef.declaringType as? IrType.Object)?.className == enumClassName
        }

        private fun producerOf(value: Operand): Instruction? {
            val insn = when (value) {
                is InstructionOperand -> value.instruction
                is RegisterOperand -> value.ssaValue?.assign?.parent
                else -> null
            } ?: return null
            return when (insn.opcode) {
                IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG ->
                    if (insn.argCount > 0) producerOf(insn.getArg(0)) else insn
                else -> insn
            }
        }

        private val PURE_PRODUCER_OPCODES = setOf(
            IrOpcode.CONST, IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS,
            IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG,
            IrOpcode.CAST, IrOpcode.CHECK_CAST, IrOpcode.ARITH, IrOpcode.NEG, IrOpcode.NOT,
            IrOpcode.INSTANCE_OF, IrOpcode.ARRAY_LENGTH, IrOpcode.ARRAY_GET,
            IrOpcode.NEW_ARRAY, IrOpcode.FILLED_NEW_ARRAY, IrOpcode.STATIC_GET,
        )

        private fun isPureProducer(insn: Instruction): Boolean = insn.opcode in PURE_PRODUCER_OPCODES

        private fun readSsaValues(insn: Instruction): Set<SsaValue> {
            val out = HashSet<SsaValue>()
            fun collect(op: Operand) {
                when (op) {
                    is RegisterOperand -> op.ssaValue?.let { out.add(it) }
                    is InstructionOperand -> for (i in 0 until op.instruction.argCount) collect(op.instruction.getArg(i))
                    else -> {}
                }
            }
            for (i in 0 until insn.argCount) collect(insn.getArg(i))
            return out
        }

        private fun residualIsClean(statements: List<Instruction>, suppressed: Set<Instruction>): Boolean {
            for (stmt in statements) {
                if (stmt in suppressed) continue
                for (v in readSsaValues(stmt)) {
                    val def = v.assign.parent ?: continue
                    if (def in suppressed) return false
                }
            }
            return true
        }

        /** The `<clinit>`/`<init>` instructions in execution order (straight-line linear body). */
        private fun flatten(method: IrMethod): List<Instruction> {
            val out = ArrayList<Instruction>()
            for (block in method.blocks) out.addAll(block.instructions)
            return out
        }
    }
}
