package com.jadxmp.codegen.java

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType

/**
 * Reconstructs a desugared `ACC_ENUM` class back into Java `enum` shape, entirely from the [IrClass]
 * structure + its `<clinit>` instructions — no pipeline pass. **jadx: EnumVisitor (design oracle)**
 *
 * A DEX enum arrives as: `ACC_ENUM` on the class (extending `java.lang.Enum`); `ACC_ENUM`
 * `static final` constant fields; a synthetic `$VALUES` array field; synthetic `values()` /
 * `valueOf(String)` methods; a `<clinit>` that does `A = new X("A", 0); … ; $VALUES = new X[]{A,…}`;
 * and a private `X(String name, int ordinal, …)` constructor. This analyzer recovers, from that shape:
 *  - the enum constants in **ordinal order** (the order their stores appear in `<clinit>`),
 *  - each constant's real constructor arguments (with the synthetic leading `name`/`ordinal` stripped),
 *  - the synthetic members to hide (`$VALUES`, `values()`, `valueOf(String)`),
 *  - the `<clinit>` enum-construction instructions to suppress (and whether the residual is empty),
 *  - the constructor's synthetic-arg count and its `super(name, ordinal)` call to drop.
 *
 * Returns `null` whenever the class is not a confidently-reconstructable enum (a non-enum, a missing
 * `$VALUES`, an unlocatable constant, or inline/anonymous constants we can't map to a field). In that
 * case the caller leaves the class untouched rather than emit a wrong enum (CLAUDE rule 4).
 */
internal class EnumReconstruction private constructor(
    val constants: List<EnumConstant>,
    val enumConstantFields: Set<IrField>,
    val hiddenFields: Set<IrField>,
    val hiddenMethods: Set<IrMethod>,
    val clinit: IrMethod?,
    val dropClinit: Boolean,
    val suppressedClinitInsns: Set<Instruction>,
    val fixedClassFlags: Int,
    /**
     * Each enum constant's (suppressed) constructor-result SSA value → that constant's field. A residual
     * `<clinit>` statement that reads such a value (e.g. an alias `MAX = <the constructor result>`)
     * renders as the constant's simple name; the constant is initialized before the static block runs, so
     * the substitution is always faithful. Passed through to the residual-block [MethodBodyWriter].
     */
    val constantResultFields: Map<SsaValue, FieldRef>,
) {
    /**
     * One reconstructed constant: its backing [field], the real (stripped) constructor [args], and the
     * declared types of those args ([argParamTypes], for overload-pinning `null` casts at emit time).
     */
    class EnumConstant(val field: IrField, val args: List<Operand>, val argParamTypes: List<IrType>)

    companion object {
        private const val ENUM_CLASS = "java.lang.Enum"

        /** The number of synthetic leading `<init>` params (`name`, then `ordinal`) — jadx skips both. */
        fun syntheticArgCount(paramOrArgCount: Int): Int = if (paramOrArgCount >= 2) 2 else paramOrArgCount

        /**
         * The `super(name, ordinal)` call to drop from an enum constructor body: an `<init>` invoke on
         * `java.lang.Enum`. Returns null if the ctor has no such call.
         */
        fun enumSuperCall(ctor: IrMethod): Instruction? =
            flatten(ctor).firstOrNull { isEnumSuperCall(it) }

        private fun isEnumSuperCall(insn: Instruction): Boolean {
            val inv = insn as? InvokeInstruction ?: return false
            if (!inv.methodRef.isConstructor) return false
            return (inv.methodRef.declaringType as? IrType.Object)?.className == ENUM_CLASS
        }

        fun analyze(cls: IrClass): EnumReconstruction? {
            if (!JavaModifiers.has(cls.accessFlags, JavaModifiers.ENUM)) return null
            // Misdetection guard: a real enum's super is java.lang.Enum.
            if ((cls.superType as? IrType.Object)?.className != ENUM_CLASS) return null

            val clsType = IrType.objectType(cls.fullName)
            val valuesField = findValuesField(cls, clsType) ?: return null

            val enumConstantFields = cls.fields.filter { isEnumConstant(it, clsType) }.toSet()

            val clinit = cls.methods.firstOrNull { it.name == "<clinit>" }
            // With constant fields present we need the <clinit> to recover order + args; without it we
            // cannot faithfully reconstruct, so bail.
            if (enumConstantFields.isNotEmpty() && clinit == null) return null

            val statements = clinit?.let { flatten(it) } ?: emptyList()

            // Locate each constant's construction, in <clinit> statement order.
            val orderedConstants = ArrayList<EnumConstant>()
            val ordinals = ArrayList<Int?>()
            val locatedFields = HashSet<IrField>()
            val constructorInsns = ArrayList<Instruction>()
            // A constant's constructor-result SSA → its field, so a residual alias store that reuses the
            // construction result (`MAX = <the FIVE_SECONDS result>`) can render as the constant's name.
            val constantResultFields = HashMap<SsaValue, FieldRef>()
            for (stmt in statements) {
                if (stmt.opcode != IrOpcode.STATIC_PUT) continue
                val fieldRef = (stmt as? com.jadxmp.ir.insn.FieldInstruction)?.fieldRef ?: continue
                val field = enumConstantFields.firstOrNull {
                    it.name == fieldRef.name &&
                        (fieldRef.declaringType as? IrType.Object)?.className == cls.fullName
                } ?: continue
                if (field in locatedFields) continue
                val ctor = tracedConstructor(stmt.getArg(stmt.argCount - 1), cls.fullName) ?: continue
                locatedFields.add(field)
                constructorInsns.add(ctor)
                ctor.result?.ssaValue?.let { constantResultFields[it] = FieldRef(clsType, field.name, field.type) }
                val n = syntheticArgCount(ctor.argCount)
                val args = if (ctor.argCount > n) (n until ctor.argCount).map { ctor.getArg(it) } else emptyList()
                val declaredParams = (ctor as? InvokeInstruction)?.methodRef?.paramTypes.orEmpty().drop(n)
                // The TRUE ordinal is the int literal passed as the ctor's 2nd synthetic arg (jadx uses
                // the $VALUES array order, which coincides). Store it so a reordered/obfuscated <clinit>
                // still emits constants in ordinal order rather than store order.
                val ordinal = if (n >= 2 && ctor.argCount >= 2) resolveIntLiteral(ctor.getArg(1)) else null
                orderedConstants.add(EnumConstant(field, args, declaredParams))
                ordinals.add(ordinal)
            }

            // Every declared constant field must have been located, or we'd silently drop a constant.
            if (locatedFields.size != enumConstantFields.size) return null

            // Reorder by the recovered ordinal literal when every constant has a distinct one; otherwise
            // keep the (stable) <clinit> store order rather than risk a wrong partial sort.
            val sortedConstants = orderByOrdinal(orderedConstants, ordinals)

            // Count enum-type constructions anywhere in <clinit> (top-level or inlined into a store); a
            // mismatch means inline/anonymous/"fake" constants (jadx's fake-field case) that we don't
            // reconstruct — bail rather than silently lose them (CLAUDE rule 4).
            val allEnumCtors = countEnumConstructors(statements, cls.fullName)
            if (allEnumCtors != orderedConstants.size) return null

            val hiddenFields = setOf(valuesField)
            val hiddenMethods = cls.methods.filter { isSyntheticEnumMethod(it, clsType) }.toSet()

            val suppressed = if (clinit != null) {
                computeSuppressed(statements, constructorInsns, locatedConstantStores(statements, cls), valuesField, cls)
            } else {
                emptySet()
            }
            // A residual (kept) `<clinit>` statement that reads a value defined by a SUPPRESSED
            // instruction would dangle — the def is gone. The ONE case we can still honor is a read of a
            // suppressed enum-constant CONSTRUCTION result (an alias `MAX = <the FIVE_SECONDS result>`):
            // the constant is initialized before the static block, so the codegen rewrites the read to the
            // constant's simple name (see [constantResultFields]). Any OTHER suppressed-def read (a dropped
            // `$VALUES` array element, etc.) has no faithful rendering, so we bail rather than emit an
            // undefined reference (CLAUDE rule 4).
            if (!residualIsClean(statements, suppressed, constantResultFields.keys)) return null
            val dropClinit = clinit != null && residualIsEmpty(statements, suppressed)

            // jadx fixAccessFlags: an enum declaration may not carry final/abstract/static.
            val fixedFlags = cls.accessFlags and
                (JavaModifiers.FINAL or JavaModifiers.ABSTRACT or JavaModifiers.STATIC).inv()

            return EnumReconstruction(
                constants = sortedConstants,
                enumConstantFields = enumConstantFields,
                hiddenFields = hiddenFields,
                hiddenMethods = hiddenMethods,
                clinit = clinit,
                dropClinit = dropClinit,
                suppressedClinitInsns = suppressed,
                fixedClassFlags = fixedFlags,
                constantResultFields = constantResultFields,
            )
        }

        // ---- field/method classification ----

        /** A `$VALUES`-style field: static array whose root element is the enum type. */
        private fun findValuesField(cls: IrClass, clsType: IrType): IrField? {
            val candidates = cls.fields.filter { f ->
                JavaModifiers.has(f.accessFlags, JavaModifiers.STATIC) &&
                    f.type is IrType.ArrayType &&
                    f.type.arrayRootElement == clsType
            }
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) return candidates[0]
            // Prefer the canonically-named synthetic backing array.
            return candidates.firstOrNull { it.name == "\$VALUES" }
                ?: candidates.firstOrNull { it.contains(AttrFlag.SYNTHETIC) }
                ?: return null // ambiguous ⇒ bail
        }

        private fun isEnumConstant(field: IrField, clsType: IrType): Boolean =
            JavaModifiers.has(field.accessFlags, JavaModifiers.ENUM) &&
                JavaModifiers.has(field.accessFlags, JavaModifiers.STATIC) &&
                JavaModifiers.has(field.accessFlags, JavaModifiers.FINAL) &&
                field.type == clsType

        /**
         * The compiler-synthesized members a Java `enum` regenerates or that only serve enum
         * construction: `values()` (clone-based), `valueOf(String)`, and the Kotlin `$values()` array
         * builder. Matched by exact signature so a user overload (`valueOf(int)`, `values(x)`) is NOT
         * hidden. `$values()` is only ever called from the (suppressed) `<clinit>`, so hiding it is safe
         * whenever reconstruction succeeds — [residualIsClean] would have bailed if a kept statement
         * still read its result.
         */
        private fun isSyntheticEnumMethod(m: IrMethod, clsType: IrType): Boolean {
            val enumArray = IrType.array(clsType)
            if (m.name == "values" && m.argTypes.isEmpty() && m.returnType == enumArray) return true
            if (m.name == "\$values" && m.argTypes.isEmpty() && m.returnType == enumArray &&
                JavaModifiers.has(m.accessFlags, JavaModifiers.STATIC)
            ) {
                return true
            }
            if (m.name == "valueOf" && m.argTypes.size == 1 &&
                (m.argTypes[0] as? IrType.Object)?.className == "java.lang.String" &&
                m.returnType == clsType
            ) {
                return true
            }
            return false
        }

        // ---- <clinit> tracing ----

        /**
         * Reorder [constants] by their recovered [ordinals] — but only when every constant has a
         * distinct ordinal. On any missing or duplicate ordinal we can't trust the reordering, so we
         * keep the stable `<clinit>` store order (which coincides with ordinal order for standard
         * javac/kotlinc output) rather than risk a wrong partial sort.
         */
        private fun orderByOrdinal(constants: List<EnumConstant>, ordinals: List<Int?>): List<EnumConstant> {
            if (constants.size <= 1) return constants
            if (ordinals.any { it == null }) return constants
            val present = ordinals.filterNotNull()
            if (present.toSet().size != present.size) return constants
            return constants.indices.sortedBy { ordinals[it]!! }.map { constants[it] }
        }

        /** Resolve [op] to its constant `Int` value (through a register/CONST/move), or null. */
        private fun resolveIntLiteral(op: Operand): Int? = when (op) {
            is LiteralOperand -> op.value.toInt()
            is InstructionOperand -> resolveIntLiteralInsn(op.instruction)
            is RegisterOperand -> op.ssaValue?.assign?.parent?.let { resolveIntLiteralInsn(it) }
        }

        private fun resolveIntLiteralInsn(insn: Instruction): Int? = when (insn.opcode) {
            IrOpcode.CONST, IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG ->
                if (insn.argCount > 0) resolveIntLiteral(insn.getArg(0)) else null
            else -> null
        }

        /** Resolve [value] (possibly via a register/move) to the enum-type constructor it holds. */
        private fun tracedConstructor(value: Operand, enumClassName: String): Instruction? {
            val insn = when (value) {
                is InstructionOperand -> value.instruction
                is RegisterOperand -> value.ssaValue?.assign?.parent
                else -> null
            } ?: return null
            return unwrapConstructor(insn, enumClassName)
        }

        private fun unwrapConstructor(insn: Instruction, enumClassName: String): Instruction? {
            if (isEnumConstructor(insn, enumClassName)) return insn
            return when (insn.opcode) {
                IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG ->
                    if (insn.argCount > 0) tracedConstructor(insn.getArg(0), enumClassName) else null
                else -> null
            }
        }

        /** Count every enum-type constructor instruction in [statements], including inlined (nested) ones. */
        private fun countEnumConstructors(statements: List<Instruction>, enumClassName: String): Int {
            var count = 0
            fun visit(insn: Instruction) {
                if (isEnumConstructor(insn, enumClassName)) count++
                for (i in 0 until insn.argCount) {
                    (insn.getArg(i) as? InstructionOperand)?.let { visit(it.instruction) }
                }
            }
            for (stmt in statements) visit(stmt)
            return count
        }

        private fun isEnumConstructor(insn: Instruction, enumClassName: String): Boolean {
            val inv = insn as? InvokeInstruction ?: return false
            if (inv.opcode != IrOpcode.CONSTRUCTOR) return false
            return (inv.methodRef.declaringType as? IrType.Object)?.className == enumClassName
        }

        /** The `<clinit>` STATIC_PUT statements that store into an enum-constant field. */
        private fun locatedConstantStores(statements: List<Instruction>, cls: IrClass): Set<Instruction> {
            val clsType = IrType.objectType(cls.fullName)
            val constFieldNames = cls.fields.filter { isEnumConstant(it, clsType) }.map { it.name }.toSet()
            return statements.filter { stmt ->
                stmt.opcode == IrOpcode.STATIC_PUT &&
                    (stmt as? com.jadxmp.ir.insn.FieldInstruction)?.let { fi ->
                        fi.fieldRef.name in constFieldNames &&
                            (fi.fieldRef.declaringType as? IrType.Object)?.className == cls.fullName
                    } == true
            }.toSet()
        }

        // ---- suppression of enum-construction in <clinit> ----

        private fun computeSuppressed(
            statements: List<Instruction>,
            constructorInsns: List<Instruction>,
            constantStores: Set<Instruction>,
            valuesField: IrField,
            cls: IrClass,
        ): Set<Instruction> {
            val suppressed = HashSet<Instruction>()
            suppressed.addAll(constructorInsns)
            suppressed.addAll(constantStores)

            // The `$VALUES = <array>` store and the array it builds (+ its element puts).
            val valuesStore = statements.firstOrNull { stmt ->
                stmt.opcode == IrOpcode.STATIC_PUT &&
                    (stmt as? com.jadxmp.ir.insn.FieldInstruction)?.fieldRef?.name == valuesField.name
            }
            if (valuesStore != null) {
                suppressed.add(valuesStore)
                val producer = producerOf(valuesStore.getArg(valuesStore.argCount - 1))
                if (producer != null) {
                    suppressed.add(producer)
                    // NEW_ARRAY case: drop every ARRAY_PUT into that same array.
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

        /** The instruction that produces [value] (the array feeding `$VALUES`), unwrapped through moves. */
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

        /** All SSA values read (in the arg tree, recursively) by [insn]. */
        private fun readSsaValues(insn: Instruction): Set<SsaValue> {
            val out = HashSet<SsaValue>()
            fun collect(op: Operand) {
                when (op) {
                    is RegisterOperand -> op.ssaValue?.let { out.add(it) }
                    is InstructionOperand -> {
                        for (i in 0 until op.instruction.argCount) collect(op.instruction.getArg(i))
                    }
                    else -> {}
                }
            }
            for (i in 0 until insn.argCount) collect(insn.getArg(i))
            return out
        }

        /**
         * True unless some kept statement reads a value whose defining instruction is suppressed — EXCEPT
         * a read of an enum-constant construction result ([rewritable]), which the codegen re-renders as
         * the constant's name (the constant is live before the static block runs).
         */
        private fun residualIsClean(
            statements: List<Instruction>,
            suppressed: Set<Instruction>,
            rewritable: Set<SsaValue>,
        ): Boolean {
            for (stmt in statements) {
                if (stmt in suppressed) continue
                for (v in readSsaValues(stmt)) {
                    if (v in rewritable) continue
                    val def = v.assign.parent ?: continue
                    if (def in suppressed) return false
                }
            }
            return true
        }

        /**
         * True when [ctor] is a synthetic-only enum constructor (no real params) whose body is nothing
         * but the `super(name, ordinal)` call and the terminal return — so the reconstructed `enum`
         * needs no explicit constructor at all (jadx: isDefaultConstructor ⇒ DONT_GENERATE).
         */
        fun isDefaultEnumConstructor(ctor: IrMethod): Boolean {
            if (ctor.argTypes.size > syntheticArgCount(ctor.argTypes.size)) return false
            val superCall = enumSuperCall(ctor)
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

        private fun residualIsEmpty(statements: List<Instruction>, suppressed: Set<Instruction>): Boolean {
            for (stmt in statements) {
                if (stmt in suppressed) continue
                when (stmt.opcode) {
                    // Control-flow scaffolding that never renders as a statement on its own.
                    IrOpcode.NOP, IrOpcode.GOTO, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
                    IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT,
                    -> continue
                    IrOpcode.RETURN -> if (stmt.argCount == 0) continue else return false
                    else -> return false
                }
            }
            return true
        }

        // ---- helpers ----

        /**
         * The method's instructions in execution order. Uses [IrMethod.blocks] (always populated,
         * reverse-postorder ⇒ execution order for the linear straight-line body an enum `<clinit>` /
         * constructor is), not the region tree — enum construction is never nested in control flow, so
         * the flat block walk is both simpler and sufficient.
         */
        private fun flatten(method: IrMethod): List<Instruction> {
            val out = ArrayList<Instruction>()
            for (block in method.blocks) out.addAll(block.instructions)
            return out
        }
    }
}
