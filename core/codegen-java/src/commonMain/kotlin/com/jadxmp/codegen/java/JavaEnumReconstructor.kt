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
 *  - **every** constant in **ordinal order** — including field-less "fake" constants (a construction
 *    present in `$VALUES` with no backing static field, seen under obfuscation) — where the order comes
 *    from the `$VALUES` array (jadx's authority), required to be a bijection onto `{0..N-1}`,
 *  - each constant's **name from its STRING-ARG** (the ctor's first arg), NOT the possibly-obfuscated
 *    backing-field name (jadx names by the string arg and renames the field),
 *  - each constant's real constructor arguments (with the synthetic leading `name`/`ordinal` stripped),
 *  - the synthetic members to hide (`$VALUES`, `values()`, `valueOf(String)`, the `$values()` builder),
 *  - the `<clinit>` enum-construction instructions to suppress (and whether the residual is empty),
 *  - the constructor's synthetic-arg count and its `super(name, ordinal)` call to drop.
 *
 * Returns `null` whenever the class is not a confidently-reconstructable enum: a non-enum, a missing
 * `$VALUES`, a construction whose string-name/ordinal/backing-field can't be recovered, a `$VALUES`
 * array that isn't a clean `{0..N-1}` bijection over the constructions, or a string-renamed constant
 * field still referenced by surviving code. In that case the caller leaves the class untouched rather
 * than emit a wrong enum (CLAUDE rule 4).
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
     * Each enum constant's (suppressed) constructor-result SSA value → a [FieldRef] bearing that
     * constant's (string-arg) NAME. A residual `<clinit>` statement that reads such a value (e.g. an alias
     * `MAX = <the constructor result>`) renders as the constant's simple name; the constant is initialized
     * before the static block runs, so the substitution is always faithful. Passed through to the
     * residual-block [MethodBodyWriter].
     */
    val constantResultFields: Map<SsaValue, FieldRef>,
    /**
     * The synthetic backing array field (`$VALUES`, possibly obfuscated to e.g. `$VLS`). Hidden from the
     * declaration, but any USER-method reference to it must be rewritten to a `values()` call, since the
     * field itself is gone (jadx: EnumVisitor replaces `$VALUES` reads with `values()`).
     */
    val valuesField: IrField,
    /**
     * The synthetic `values()` clone method (`return ($VALUES).clone()`), possibly obfuscated (e.g.
     * `vs()`). Hidden (Java regenerates `values()`); user references to it are rewritten to `values()`.
     */
    val syntheticValuesMethod: IrMethod?,
    /**
     * The synthetic `valueOf(String)` method (`return (T) Enum.valueOf(T.class, s)`), possibly obfuscated
     * (e.g. `vo()`). Hidden (Java regenerates `valueOf`); user references are rewritten to `valueOf(...)`.
     */
    val syntheticValueOfMethod: IrMethod?,
    /**
     * User-declared methods whose signature collides with the compiler-regenerated `values()`/
     * `valueOf(String)` (a Java `enum` may not re-declare them), mapped to the fresh name they are
     * renamed to (jadx: RenameVisitor — the "custom values method kept and renamed" case). These methods
     * are KEPT (never dropped — that would be silent code loss); only their name changes.
     */
    val renamedMethods: Map<IrMethod, String>,
) {
    /**
     * One reconstructed constant. [name] is the Java constant name — the (sanitized) STRING-ARG passed to
     * the constructor, NOT the (possibly obfuscated) backing field name (jadx names by the string arg).
     * [field] is the backing `ACC_ENUM` static field, or `null` for a **fake** constant: a construction
     * present in `<clinit>` / `$VALUES` with no backing field (obfuscation that kept the ordinal live but
     * dropped the field). [args] are the real (synthetic-`name`/`ordinal`-stripped) constructor arguments
     * and [argParamTypes] their declared parameter types (for overload-pinning `null` casts at emit time).
     */
    class EnumConstant(
        val name: String,
        val field: IrField?,
        val args: List<Operand>,
        val argParamTypes: List<IrType>,
    )

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

            // Recover EVERY constant — field-backed AND field-less "fake" (a construction with no backing
            // static field: an obfuscator that kept the ordinal live in `$VALUES` but dropped the field) —
            // in ordinal order, naming each by its STRING-ARG rather than the (possibly obfuscated) field
            // name (jadx: EnumVisitor). Returns null on ANY ambiguity so we never drop, duplicate,
            // mis-order, or mis-name a constant (CLAUDE rule 4).
            val recovery = recoverConstants(cls, clsType, statements, enumConstantFields, valuesField) ?: return null

            val hiddenFields = setOf(valuesField)
            val (hiddenMethods, renamedMethods, syntheticValues, syntheticValueOf) =
                classifyEnumMethods(cls, clsType, valuesField)

            val suppressed = if (clinit != null) {
                computeSuppressed(
                    statements, recovery.constructors,
                    locatedConstantStores(statements, cls), valuesField, cls,
                )
            } else {
                emptySet()
            }

            // A constant named by its string-arg whose backing FIELD name differs is effectively renamed
            // (the field is hidden, the constant re-declared under the string name). A codegen-only backend
            // cannot rewrite every reference to that now-gone field, so if any surviving (non-suppressed)
            // read/write of it exists — here or in another class — bail to the honest raw form rather than
            // emit a dangling reference (CLAUDE rule 4). For an un-referenced field (the common obfuscated
            // case) the rename is invisible and safe.
            if (renamedConstantFieldReferenced(cls, recovery.constants, suppressed)) return null

            // A residual (kept) `<clinit>` statement that reads a value defined by a SUPPRESSED
            // instruction would dangle — the def is gone. The ONE case we can still honor is a read of a
            // suppressed enum-constant CONSTRUCTION result (an alias `MAX = <the FIVE_SECONDS result>`):
            // the constant is initialized before the static block, so the codegen rewrites the read to the
            // constant's simple name (see [constantResultFields]). Any OTHER suppressed-def read (a dropped
            // `$VALUES` array element, etc.) has no faithful rendering, so we bail rather than emit an
            // undefined reference (CLAUDE rule 4).
            if (!residualIsClean(statements, suppressed, recovery.constantResultFields.keys)) return null
            val dropClinit = clinit != null && residualIsEmpty(statements, suppressed)

            // jadx fixAccessFlags: an enum declaration may not carry final/abstract/static.
            val fixedFlags = cls.accessFlags and
                (JavaModifiers.FINAL or JavaModifiers.ABSTRACT or JavaModifiers.STATIC).inv()

            return EnumReconstruction(
                constants = recovery.constants,
                enumConstantFields = enumConstantFields,
                hiddenFields = hiddenFields,
                hiddenMethods = hiddenMethods,
                clinit = clinit,
                dropClinit = dropClinit,
                suppressedClinitInsns = suppressed,
                fixedClassFlags = fixedFlags,
                constantResultFields = recovery.constantResultFields,
                valuesField = valuesField,
                syntheticValuesMethod = syntheticValues,
                syntheticValueOfMethod = syntheticValueOf,
                renamedMethods = renamedMethods,
            )
        }

        /**
         * Result of [recoverConstants]: the constants in ordinal order, EVERY enum construction found in
         * `<clinit>` (field-backed + fake — for suppression), and the construction-result → constant-name
         * alias map for residual-block rewrites.
         */
        private class Recovery(
            val constants: List<EnumConstant>,
            val constructors: List<Instruction>,
            val constantResultFields: Map<SsaValue, FieldRef>,
        )

        /**
         * Recover the enum's constants — field-backed AND field-less "fake" — in ordinal order, PROVABLY:
         *  1. collect every enum-type construction in `<clinit>` (top-level or inlined),
         *  2. map each construction to its backing field via the `STATIC_PUT` that stores its result,
         *  3. derive the ordinal ORDER from the `$VALUES` array (jadx's authority), requiring the array to
         *     be a bijection onto `{0..N-1}` over exactly those constructions — proving none is dropped,
         *     duplicated, or mis-ordered,
         *  4. name each constant by its (sanitized) string-arg, requiring distinct names,
         *  5. require every declared constant field to back exactly one construction.
         * Any failure returns null (the caller bails). An enum with no constructions and no constant fields
         * is legitimately empty (recovered as zero constants).
         */
        private fun recoverConstants(
            cls: IrClass,
            clsType: IrType,
            statements: List<Instruction>,
            enumConstantFields: Set<IrField>,
            valuesField: IrField,
        ): Recovery? {
            val allCtors = collectEnumConstructors(statements, cls.fullName)
            if (allCtors.isEmpty()) {
                // A declared constant field with no construction ⇒ we can't recover its args ⇒ bail.
                if (enumConstantFields.isNotEmpty()) return null
                return Recovery(emptyList(), emptyList(), emptyMap())
            }
            val allCtorSet = allCtors.toHashSet()

            // Construction ↔ backing field, via the STATIC_PUT of the construction result into the field.
            val fieldByCtor = HashMap<Instruction, IrField>()
            for (stmt in statements) {
                if (stmt.opcode != IrOpcode.STATIC_PUT) continue
                val fieldRef = (stmt as? com.jadxmp.ir.insn.FieldInstruction)?.fieldRef ?: continue
                if ((fieldRef.declaringType as? IrType.Object)?.className != cls.fullName) continue
                val field = enumConstantFields.firstOrNull { it.name == fieldRef.name } ?: continue
                val ctor = tracedConstructor(stmt.getArg(stmt.argCount - 1), cls.fullName) ?: continue
                if (ctor in allCtorSet && ctor !in fieldByCtor) fieldByCtor[ctor] = field
            }
            // Every declared constant field must back exactly one distinct construction.
            if (fieldByCtor.size != enumConstantFields.size) return null
            if (fieldByCtor.values.toHashSet().size != enumConstantFields.size) return null
            val ctorByField = fieldByCtor.entries.associate { (c, f) -> f to c }

            // Ordinal order + completeness, from $VALUES (the authoritative order jadx also uses).
            val ordered = orderFromValuesArray(statements, valuesField, cls, allCtors, allCtorSet, ctorByField, enumConstantFields)
                ?: return null

            val constants = ArrayList<EnumConstant>(ordered.size)
            val resultFields = HashMap<SsaValue, FieldRef>()
            val usedNames = HashSet<String>()
            for ((index, ctor) in ordered.withIndex()) {
                val inv = ctor as? InvokeInstruction ?: return null
                if (ctor.argCount < 1) return null
                val rawName = resolveStringLiteral(ctor.getArg(0)) ?: return null
                // Sanitize (as jadx does) so an illegal-identifier name still yields compilable output; a
                // post-sanitize collision (two names collapse) is unrecoverable ⇒ bail.
                val name = JavaIdentifiers.sanitize(rawName)
                if (!usedNames.add(name)) return null
                // Cross-check: when the ctor carries an explicit ordinal arg it must equal the array index.
                if (ctor.argCount >= 2) {
                    val ordinal = resolveIntLiteral(ctor.getArg(1))
                    if (ordinal != null && ordinal != index) return null
                }
                val n = syntheticArgCount(ctor.argCount)
                val args = if (ctor.argCount > n) (n until ctor.argCount).map { ctor.getArg(it) } else emptyList()
                val declaredParams = inv.methodRef.paramTypes.drop(n)
                constants.add(EnumConstant(name, fieldByCtor[ctor], args, declaredParams))
                // Residual alias reads of this construction render as the constant's (string) name; the
                // FieldRef carries that name so aliasForFieldRef falls through to it (no such field exists).
                ctor.result?.ssaValue?.let { resultFields[it] = FieldRef(clsType, name, clsType) }
            }
            return Recovery(constants, allCtors, resultFields)
        }

        /**
         * Derive the constants' ordinal order from the `$VALUES` array, requiring the array to fill
         * indices `{0..N-1}` exactly once each with a distinct construction covering EXACTLY [allCtors] —
         * a bijection. Array elements may be either the construction result directly (`aput vNew`) or an
         * `sget` of the just-stored constant field (`aput <sget CONST>`); both resolve to the construction.
         * The array itself is built either inline in `<clinit>` (older javac) or in a synthetic `$values()`
         * builder method the `<clinit>` calls (modern javac/kotlinc) — [resolveArrayBuild] normalizes both.
         * Returns the constructions in index order, or null on any gap/duplicate/unresolved element.
         */
        private fun orderFromValuesArray(
            statements: List<Instruction>,
            valuesField: IrField,
            cls: IrClass,
            allCtors: List<Instruction>,
            allCtorSet: Set<Instruction>,
            ctorByField: Map<IrField, Instruction>,
            enumConstantFields: Set<IrField>,
        ): List<Instruction>? {
            val valuesStore = statements.firstOrNull { stmt ->
                stmt.opcode == IrOpcode.STATIC_PUT &&
                    (stmt as? com.jadxmp.ir.insn.FieldInstruction)?.let {
                        it.fieldRef.name == valuesField.name &&
                            (it.fieldRef.declaringType as? IrType.Object)?.className == cls.fullName
                    } == true
            } ?: return null
            val storedProducer = producerOf(valuesStore.getArg(valuesStore.argCount - 1)) ?: return null
            // Normalize to the statement list + array-producer where the elements actually live.
            val (arrayStmts, producer) = resolveArrayBuild(storedProducer, statements, cls) ?: return null
            val indexToCtor = HashMap<Int, Instruction>()
            when (producer.opcode) {
                IrOpcode.FILLED_NEW_ARRAY -> {
                    for (i in 0 until producer.argCount) {
                        val ctor = ctorForArrayElement(producer.getArg(i), cls, allCtorSet, ctorByField, enumConstantFields)
                            ?: return null
                        if (indexToCtor.put(i, ctor) != null) return null
                    }
                }
                IrOpcode.NEW_ARRAY -> {
                    val size = if (producer.argCount > 0) resolveIntLiteral(producer.getArg(0)) else null
                    val arrayVar = producer.result?.ssaValue ?: return null
                    for (stmt in arrayStmts) {
                        if (stmt.opcode != IrOpcode.ARRAY_PUT || stmt.argCount < 3) continue
                        if ((stmt.getArg(1) as? RegisterOperand)?.ssaValue != arrayVar) continue
                        val index = resolveIntLiteral(stmt.getArg(2)) ?: return null
                        if (index < 0) return null
                        val ctor = ctorForArrayElement(stmt.getArg(0), cls, allCtorSet, ctorByField, enumConstantFields)
                            ?: return null
                        if (indexToCtor.put(index, ctor) != null) return null // duplicate index ⇒ bail
                    }
                    if (size != null && indexToCtor.size != size) return null
                }
                else -> return null
            }
            val n = indexToCtor.size
            if (n != allCtors.size) return null // array must cover exactly the constructions
            for (i in 0 until n) if (i !in indexToCtor) return null // indices must be 0..N-1
            val ordered = (0 until n).map { indexToCtor.getValue(it) }
            if (ordered.toHashSet().size != n) return null // distinct constructions (no duplicate)
            if (!ordered.all { it in allCtorSet }) return null
            return ordered
        }

        /**
         * Normalize the `$VALUES` array-building site to `(statements, arrayProducer)`:
         *  - **inline** (older javac): the store's value IS a `NEW_ARRAY`/`FILLED_NEW_ARRAY` in `<clinit>`
         *    → the `<clinit>` statements and that producer.
         *  - **`$values()` builder** (modern javac/kotlinc): the store's value is the RESULT of an invoke
         *    of a synthetic static array-builder → that method's body statements and the array it returns.
         * Returns null when the value is neither shape (e.g. an unrecognized producer) so the caller bails.
         */
        private fun resolveArrayBuild(
            producer: Instruction,
            statements: List<Instruction>,
            cls: IrClass,
        ): Pair<List<Instruction>, Instruction>? {
            when (producer.opcode) {
                IrOpcode.NEW_ARRAY, IrOpcode.FILLED_NEW_ARRAY -> return statements to producer
                IrOpcode.INVOKE -> {
                    val inv = producer as? InvokeInstruction ?: return null
                    if (inv.invokeKind != com.jadxmp.ir.insn.InvokeKind.STATIC) return null
                    if ((inv.methodRef.declaringType as? IrType.Object)?.className != cls.fullName) return null
                    val builder = cls.methods.firstOrNull {
                        it.name == inv.methodRef.name && it.argTypes == inv.methodRef.paramTypes &&
                            JavaModifiers.has(it.accessFlags, JavaModifiers.STATIC)
                    } ?: return null
                    val builderStmts = flatten(builder)
                    val ret = builderStmts.lastOrNull { it.opcode == IrOpcode.RETURN && it.argCount == 1 } ?: return null
                    val arrayProducer = producerOf(ret.getArg(0)) ?: return null
                    if (arrayProducer.opcode != IrOpcode.NEW_ARRAY && arrayProducer.opcode != IrOpcode.FILLED_NEW_ARRAY) {
                        return null
                    }
                    return builderStmts to arrayProducer
                }
                else -> return null
            }
        }

        /** The construction a `$VALUES` array element refers to — directly, or via an `sget` of its field. */
        private fun ctorForArrayElement(
            element: Operand,
            cls: IrClass,
            allCtorSet: Set<Instruction>,
            ctorByField: Map<IrField, Instruction>,
            enumConstantFields: Set<IrField>,
        ): Instruction? {
            tracedConstructor(element, cls.fullName)?.let { if (it in allCtorSet) return it }
            val insn = producerOf(element) ?: return null
            if (insn.opcode == IrOpcode.STATIC_GET) {
                val fieldRef = (insn as? com.jadxmp.ir.insn.FieldInstruction)?.fieldRef ?: return null
                if ((fieldRef.declaringType as? IrType.Object)?.className != cls.fullName) return null
                val field = enumConstantFields.firstOrNull { it.name == fieldRef.name } ?: return null
                return ctorByField[field]
            }
            return null
        }

        /** Every enum-type construction in [statements], including ones inlined into another instruction. */
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

        /** Resolve [op] to its constant `String` value (through a register/CONST_STRING/move), or null. */
        private fun resolveStringLiteral(op: Operand): String? = when (op) {
            is InstructionOperand -> resolveStringLiteralInsn(op.instruction)
            is RegisterOperand -> op.ssaValue?.assign?.parent?.let { resolveStringLiteralInsn(it) }
            is LiteralOperand -> null
        }

        private fun resolveStringLiteralInsn(insn: Instruction): String? = when (insn.opcode) {
            IrOpcode.CONST_STRING -> (insn as? com.jadxmp.ir.insn.ConstStringInstruction)?.value
            IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG ->
                if (insn.argCount > 0) resolveStringLiteral(insn.getArg(0)) else null
            else -> null
        }

        /**
         * True if any constant renamed by its string-arg (its sanitized string name differs from its
         * sanitized backing-field name) has a surviving reference to that field — a non-suppressed
         * read/write, in this class or ANY other loaded class. Such a reference cannot be rewritten by a
         * codegen-only backend, so its presence forces a bail (see [analyze]).
         */
        private fun renamedConstantFieldReferenced(
            cls: IrClass,
            constants: List<EnumConstant>,
            suppressed: Set<Instruction>,
        ): Boolean {
            val renamedFieldNames = constants.mapNotNull { c ->
                c.field?.name?.takeIf { JavaIdentifiers.sanitize(it) != c.name }
            }.toHashSet()
            if (renamedFieldNames.isEmpty()) return false
            for (other in cls.root.classes) {
                for (m in other.methods) {
                    for (block in m.blocks) {
                        for (insn in block.instructions) {
                            if (insn in suppressed) continue // a suppressed <clinit> statement is dropped
                            if (referencesEnumField(insn, cls.fullName, renamedFieldNames)) return true
                        }
                    }
                }
            }
            return false
        }

        private fun referencesEnumField(insn: Instruction, enumClassName: String, fieldNames: Set<String>): Boolean {
            val fieldRef = (insn as? com.jadxmp.ir.insn.FieldInstruction)?.fieldRef
            if (fieldRef != null && fieldRef.name in fieldNames &&
                (fieldRef.declaringType as? IrType.Object)?.className == enumClassName
            ) {
                return true
            }
            for (i in 0 until insn.argCount) {
                val arg = insn.getArg(i)
                if (arg is InstructionOperand && referencesEnumField(arg.instruction, enumClassName, fieldNames)) return true
            }
            return false
        }

        /** Result of [classifyEnumMethods]: which methods to hide, which to rename, and the two synthetics. */
        private data class MethodClassification(
            val hidden: Set<IrMethod>,
            val renamed: Map<IrMethod, String>,
            val syntheticValues: IrMethod?,
            val syntheticValueOf: IrMethod?,
        )

        /**
         * The rename map alone, derivable from a class's STRUCTURE (no `<clinit>` analysis) — used by the
         * codegen backend to spell a CROSS-CLASS call to a renamed reserved-signature method with the same
         * name the (owning class's) definition uses. The caller additionally confirms full reconstruction
         * succeeds (`analyze(cls) != null`) before honoring it, so the two sides always agree.
         */
        fun plannedMemberRenames(cls: IrClass): Map<IrMethod, String> {
            if (!JavaModifiers.has(cls.accessFlags, JavaModifiers.ENUM)) return emptyMap()
            if ((cls.superType as? IrType.Object)?.className != ENUM_CLASS) return emptyMap()
            val clsType = IrType.objectType(cls.fullName)
            val valuesField = findValuesField(cls, clsType) ?: return emptyMap()
            return classifyEnumMethods(cls, clsType, valuesField).renamed
        }

        /**
         * Partition the class's methods into the compiler-synthesized enum helpers to HIDE (`values()`
         * clone, `valueOf(String)`, Kotlin `$values()` builder) and any USER method that collides with a
         * regenerated helper's signature and must be RENAMED rather than dropped.
         *
         * ## Detecting the synthetics without dropping a user method (CLAUDE rule 4)
         * - **`values()` clone** — matched STRUCTURALLY (`$VALUES.clone()`), since the reserved *name*
         *   `values` may belong to a genuine user method while the clone is obfuscated (`vs()`). The dual
         *   read-`$VALUES`+`clone()` shape is tight. AMBIGUITY GUARD: a synthetic is hidden only when
         *   EXACTLY ONE method matches; two matches ⇒ hide neither (leaking the real clone is uglier but
         *   correct — hiding the wrong one is silent code loss).
         * - **`valueOf(String)`** — matched by CANONICAL NAME first: `java.lang.Enum.valueOf` is a *public*
         *   API a user may legally wrap, so a body-shape match is NOT proof of syntheticity. A Java `enum`
         *   cannot re-declare `valueOf(String)`, so the method literally named `valueOf` (if any) is always
         *   THE synthetic. Only when NO method is named `valueOf` (genuine obfuscation, e.g. `vo()`) do we
         *   fall to the structural shape — again under the single-match ambiguity guard.
         *
         * A reserved-*signature* method that is NOT the chosen synthetic is a user method colliding with
         * the regenerated helper and is RENAMED (kept), never hidden. When no clone exists at all (standard
         * javac / body-less test fixtures) a canonical `values()` IS the synthetic and is hidden.
         */
        private fun classifyEnumMethods(cls: IrClass, clsType: IrType, valuesField: IrField): MethodClassification {
            val enumArray = IrType.array(clsType)

            // `values()` clone — structural, single-match only.
            val cloneCandidates = cls.methods.filter { isValuesCloneShape(it, cls, clsType, valuesField) }
            val syntheticValues = cloneCandidates.singleOrNull()

            // `valueOf(String)` — canonical name wins; else structural, single-match only.
            val syntheticValueOf = cls.methods.singleOrNull { isCanonicalValueOf(it, clsType) }
                ?: cls.methods.filter { isValueOfShape(it, clsType) }.singleOrNull()

            val hidden = HashSet<IrMethod>()
            val renamed = HashMap<IrMethod, String>()
            val usedNames = cls.methods.mapNotNull { if (isSpecialName(it.name)) null else it.name }.toHashSet()
            for (m in cls.methods) {
                when {
                    m === syntheticValues || m === syntheticValueOf -> hidden.add(m)
                    isDollarValuesBuilder(m, enumArray) -> hidden.add(m)
                    isCanonicalValues(m, enumArray) ->
                        // A `values()`-signature method that is not the chosen clone. If ANY clone exists,
                        // m is a user method colliding with the regenerated `values()` ⇒ keep + rename. If
                        // no clone exists at all, m IS the (standard/body-less) synthetic ⇒ hide.
                        if (cloneCandidates.isEmpty()) hidden.add(m) else renamed[m] = freshName("valuesCustom", usedNames)
                }
            }
            return MethodClassification(hidden, renamed, syntheticValues, syntheticValueOf)
        }

        private fun isSpecialName(name: String): Boolean = name == "<init>" || name == "<clinit>"

        /** [base] if free within [used], else `base2`, `base3`, …; records and returns the pick. */
        private fun freshName(base: String, used: MutableSet<String>): String {
            if (used.add(base)) return base
            var n = 2
            while (!used.add("$base$n")) n++
            return "$base$n"
        }

        private fun isCanonicalValues(m: IrMethod, enumArray: IrType): Boolean =
            m.name == "values" && m.argTypes.isEmpty() && m.returnType == enumArray

        private fun isCanonicalValueOf(m: IrMethod, clsType: IrType): Boolean =
            m.name == "valueOf" && m.argTypes.size == 1 &&
                (m.argTypes[0] as? IrType.Object)?.className == "java.lang.String" &&
                m.returnType == clsType

        private fun isDollarValuesBuilder(m: IrMethod, enumArray: IrType): Boolean =
            m.name == "\$values" && m.argTypes.isEmpty() && m.returnType == enumArray &&
                JavaModifiers.has(m.accessFlags, JavaModifiers.STATIC)

        /**
         * The `values()` clone shape: a `static T[] X()` whose body reads [valuesField] and calls
         * `clone()`. The dual read+clone requirement is what makes this provable enough to hide (rule 4).
         */
        private fun isValuesCloneShape(m: IrMethod, cls: IrClass, clsType: IrType, valuesField: IrField): Boolean =
            JavaModifiers.has(m.accessFlags, JavaModifiers.STATIC) &&
                m.argTypes.isEmpty() &&
                m.returnType == IrType.array(clsType) &&
                readsValuesField(m, cls, valuesField) &&
                callsClone(m)

        /** The structural `valueOf(String)` shape: a `static T X(String)` whose body calls `Enum.valueOf`. */
        private fun isValueOfShape(m: IrMethod, clsType: IrType): Boolean =
            JavaModifiers.has(m.accessFlags, JavaModifiers.STATIC) &&
                m.argTypes.size == 1 &&
                (m.argTypes[0] as? IrType.Object)?.className == "java.lang.String" &&
                m.returnType == clsType &&
                callsEnumValueOf(m)

        private fun readsValuesField(m: IrMethod, cls: IrClass, valuesField: IrField): Boolean =
            flatten(m).any { insn ->
                insn.opcode == IrOpcode.STATIC_GET &&
                    (insn as? com.jadxmp.ir.insn.FieldInstruction)?.fieldRef?.let {
                        it.name == valuesField.name &&
                            (it.declaringType as? IrType.Object)?.className == cls.fullName
                    } == true
            }

        private fun callsClone(m: IrMethod): Boolean =
            flatten(m).any { (it as? InvokeInstruction)?.methodRef?.name == "clone" }

        private fun callsEnumValueOf(m: IrMethod): Boolean =
            flatten(m).any { insn ->
                val inv = insn as? InvokeInstruction ?: return@any false
                inv.methodRef.name == "valueOf" &&
                    (inv.methodRef.declaringType as? IrType.Object)?.className == ENUM_CLASS
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

        // ---- <clinit> tracing ----

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
