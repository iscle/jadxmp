package com.jadxmp.codegen.java

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.CodeWriter
import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.ImportCollector
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.NameGenerator
import com.jadxmp.codegen.VarRef
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.FillArrayInstruction
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeCustomInstruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.region.CatchClause
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopKind
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind

/**
 * Opcodes an enum-constant argument may inline through — only producers that are genuinely
 * **self-contained expressions** in the `<clinit>` context. Shared as the single source of truth
 * between the RENDERER ([MethodBodyWriter.canInline]/`inlinableDef`, which folds an arg into an inline
 * expression) and the ANALYZER ([EnumReconstruction], which computes exactly the same inlined-instruction
 * set to suppress from the residual `<clinit>`). They MUST agree: a set the renderer inlines but the
 * analyzer fails to suppress leaves a dangling residual statement; the reverse would drop a live insn.
 *
 * Deliberately EXCLUDES:
 *  - `NEW_ARRAY` — a sized `new T[n]` whose elements are filled by SEPARATE `ARRAY_PUT` statements
 *    living outside this expression DAG; a bare inline would emit an empty array and silently drop every
 *    element (rule 4). [EnumReconstruction] instead FOLDS a `NEW_ARRAY`+dense-`ARRAY_PUT` run into a
 *    `new T[]{…}` array literal; only `FILLED_NEW_ARRAY` (elements carried as args) is inline-safe here.
 *  - `CONSTRUCTOR` — inlining `new X(…)` for a reference to ANOTHER enum constant (whose SSA def is the
 *    enum-self constructor) is illegal Java and fabricates a fresh instance. [EnumReconstruction] instead
 *    resolves such a reference to the referenced constant's NAME (gated backward-ordinal-only).
 */
internal val INLINABLE_ENUM_ARG_OPCODES = setOf(
    IrOpcode.CONST, IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS,
    IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG,
    IrOpcode.CAST, IrOpcode.CHECK_CAST, IrOpcode.ARITH, IrOpcode.NEG, IrOpcode.NOT,
    IrOpcode.INSTANCE_OF, IrOpcode.ARRAY_LENGTH, IrOpcode.ARRAY_GET,
    IrOpcode.FILLED_NEW_ARRAY, IrOpcode.STATIC_GET, IrOpcode.INSTANCE_GET,
    IrOpcode.INVOKE, IrOpcode.TERNARY, IrOpcode.STRING_CONCAT,
)

/**
 * Cap on inlined-expression nesting depth (CLAUDE rule-4 fault isolation). `ExpressionShaping` folds each
 * single-use def into its use as a nested operand to a FIXPOINT, so a length-N single-use chain — a big
 * arithmetic / string-concat / nested-ternary run, routine in generated & obfuscated code — collapses into
 * ONE N-deep expression, which [MethodBodyWriter.emitInsnExpr] renders with N-deep recursion → a
 * `StackOverflowError`. An SOE is an [Error], not an [Exception], so the historical per-member
 * `catch(Exception)` guards missed it, and on wasmJs the stack is far shallower than the JVM's. We bound
 * the depth FAR above any faithful nesting (real code is a few dozen deep at most) yet FAR below overflow,
 * and THROW past it so the per-member backstop rolls the whole method back to one honest marker — a
 * proactive defense that never relies on catching a real SOE. (jadx instead catches the SOE in
 * `MethodGen.addRegionInsns`; we keep it from arising.) Verified transparent to accurate output: no valid
 * corpus sample nests anywhere near this, so the differential oracle is unchanged.
 */
private const val MAX_EXPR_DEPTH = 300

/** Thrown by [MethodBodyWriter.emitInsnExpr] past [MAX_EXPR_DEPTH]; caught by the per-member backstop (rule 4). */
private class ExpressionTooDeepException :
    RuntimeException("expression nesting exceeded $MAX_EXPR_DEPTH (single-use inline chain or cycle)")

/**
 * Emits one method body: statements, expression trees (with correct precedence), conditions, and the
 * structured region tree. **jadx: MethodGen + RegionGen + InsnGen + ConditionGen**
 *
 * Holds the per-method state — a [NameGenerator] and the variable → [VarRef] map — so a variable keeps
 * one name and one stable metadata id throughout the body. Two body paths exist:
 *  - a [Region] tree present ⇒ [emitRegion] walks it into structured `if`/`for`/`while`/`switch`/
 *    `try`/`synchronized`;
 *  - no region yet ⇒ a linear fallback: one block emits straight-line statements; several blocks emit a
 *    best-effort labeled-block form (a pre-structuring stopgap that need not compile for arbitrary
 *    control flow).
 */
internal class MethodBodyWriter(
    private val code: CodeWriter,
    imports: ImportCollector,
    private val method: IrMethod,
    private val names: NameGenerator,
    paramNames: List<String>,
    // Instructions the caller has already accounted for elsewhere (an enum `<clinit>`'s
    // enum-construction, whose stores become entry declarations / are synthesized by the compiler) and
    // that must not render as body statements. See [JavaCodeGenerator] enum handling.
    private val suppressed: Set<Instruction> = emptySet(),
    // Maps an enum constant's (suppressed) constructor-result SSA value to that constant's field. A read
    // of such a value in the residual `<clinit>` (e.g. `MAX = <the FIVE_SECONDS constructor result>`)
    // renders as the enum constant's simple name instead of a now-undefined temporary — the reconstructed
    // enum initializes its constants BEFORE the static block runs, so the constant is always in scope and
    // holds exactly that instance, making the substitution unconditionally faithful. See
    // [EnumReconstruction]. Empty for every non-enum-`<clinit>` body.
    private val enumConstantResultFields: Map<SsaValue, FieldRef> = emptyMap(),
    // Reference rewrites for an enum whose synthetic `$VALUES` field / `values()` clone / `valueOf`
    // helpers were obfuscated and hidden: a USER-method reference to any of them must be redirected to
    // the compiler-regenerated `values()`/`valueOf` (the hidden originals are gone), and a user method
    // renamed to dodge a `values()`/`valueOf` collision must be CALLED by its new name. Null off the enum
    // path. See [EnumReconstruction] / [JavaCodeGenerator].
    private val enumRewrites: EnumRefRewrites? = null,
    // Deobfuscation/user rename overrides, applied to member call/read sites and renamed-class type refs
    // in this body. [AliasMap.EMPTY] (the default) ⇒ the byte-identical no-deobfuscation path.
    private val aliasMap: AliasMap = AliasMap.EMPTY,
) {
    private val types = JavaTypeRenderer(imports, aliasMap, method.declaringClass.root)

    /**
     * The enum-reference rewrites [MethodBodyWriter] applies while emitting an obfuscated enum's user
     * methods. All matching is by the enum's own (class, member) identity so a same-named member of
     * another type is never touched.
     */
    internal class EnumRefRewrites(
        /** The reconstructed enum's binary name — the owner every rewrite is scoped to. */
        val enumClassName: String,
        /** The hidden `$VALUES`-style field name: a static read of it renders as `values()`. */
        val valuesFieldName: String,
        /** The hidden `values()` clone method name (may be obfuscated): a call renders as `values()`. */
        val cloneMethodName: String?,
        /** The hidden `valueOf(String)` method name (may be obfuscated): a call renders as `valueOf(...)`. */
        val valueOfMethodName: String?,
        /** User methods renamed to dodge a collision, keyed `name(paramSig)` → new name (for call sites). */
        val methodRenames: Map<String, String>,
    ) {
        companion object {
            fun signatureKey(name: String, paramTypes: List<IrType>): String =
                "$name(${paramTypes.joinToString(",") { it.toString() }})"
        }
    }

    // When set, a RegisterOperand renders as its (inlined) defining expression rather than a variable
    // name — used to render enum-constant constructor arguments outside the `<clinit>` variable scope,
    // where the source registers have no declaration. Only enabled during [emitEnumConstantArgs].
    private var inlineRegisters = false

    // Whether the enclosing class renders as a reconstructed enum (so its `<init>` params have the
    // synthetic `name`/`ordinal` stripped in the declaration). Computed at most once, and only when a
    // same-class constructor delegation is actually emitted.
    private var enclosingEnumComputed = false
    private var enclosingEnum = false

    private fun enclosingIsReconstructedEnum(): Boolean {
        if (!enclosingEnumComputed) {
            enclosingEnum = EnumReconstruction.analyze(method.declaringClass) != null
            enclosingEnumComputed = true
        }
        return enclosingEnum
    }

    // Identity/value keyed: LocalVar or SsaValue (identity, no equals override) or a boxed regNum (value).
    private val varRefs = HashMap<Any, VarRef>()
    private val declared = HashSet<Any>()
    private var nextVarId = 0

    // Current expression-tree nesting depth (rule-4 F2 guard — see [MAX_EXPR_DEPTH]). A fresh
    // MethodBodyWriter renders each member, so this counter never leaks across methods.
    private var exprDepth = 0

    // The <clinit>'s TERMINAL return-void, if any: the fall-off `return;` that is illegal inside a
    // `static { … }` block. Only this exact instruction is suppressed — an early/conditional return-void
    // is left to render (an honest, recompile-failing `return;`) rather than silently dropped, which
    // would let its guarded tail run unconditionally (no-silent-code-loss).
    private val clinitTerminalReturn: Instruction? = findClinitTerminalReturn()

    init {
        // Parameter names are reserved so generated local names never collide with them. The names are
        // linked to the body via each parameter's LocalVar name (set by the pipeline / test).
        for (p in paramNames) names.reserve(p)
    }

    // ---------- body entry ----------

    fun writeBody() {
        val region = method.region
        if (region != null) {
            emitRegion(region)
            return
        }
        val blocks = method.blocks
        when {
            blocks.isEmpty() -> {}
            isLinear(blocks) -> blocks.forEach { emitStatements(it) }
            else -> emitLabeledBlockFallback(blocks)
        }
    }

    /**
     * A branch-free, acyclic method: no block forks and no back-edge, so once the (possibly empty)
     * synthetic entry/exit blocks are concatenated the body is a single straight-line path. Emitting the
     * blocks' statements in reverse-postorder (execution order) yields flat, compilable Java — the
     * Phase-2 form before region structuring exists. Anything with real control flow falls through to
     * the labeled-block stopgap.
     */
    private fun isLinear(blocks: List<BasicBlock>): Boolean {
        val orderOf = HashMap<Int, Int>(blocks.size)
        blocks.forEachIndexed { i, b -> orderOf[b.id] = i }
        blocks.forEachIndexed { i, b ->
            if (b.successors.size > 1) return false
            for (s in b.successors) {
                val si = orderOf[s.id] ?: return false
                if (si <= i) return false // back edge ⇒ a loop ⇒ not linear
            }
        }
        return true
    }

    private fun emitLabeledBlockFallback(blocks: List<BasicBlock>) {
        // Pre-structuring stopgap: labeled blocks, no attempt to reconstruct control flow. The
        // `blockN:` labels are honesty markers ⇒ flag the method so error accounting sees them.
        // (RenderabilityGuard already flags such non-linear methods upstream; this keeps the coupling
        // self-contained in the backend and idempotent — belt-and-suspenders, matching the Kotlin backend.)
        flagError(method, "unstructured control flow (pre-structuring block fallback)")
        for (b in blocks) {
            code.add("block").add(b.id.toString()).add(": {").newLine()
            code.incIndent()
            emitStatements(b)
            code.decIndent()
            code.add("}").newLine()
        }
    }

    private fun emitStatements(block: BasicBlock) {
        for (insn in block.instructions) emitStatement(insn)
    }

    // ---------- region walk ----------

    private fun emitRegion(region: Region) {
        when (region) {
            is SequenceRegion -> region.children.forEach { emitContainer(it) }
            is IfRegion -> emitIf(region)
            is LoopRegion -> emitLoop(region)
            is SwitchRegion -> emitSwitch(region)
            is TryCatchRegion -> emitTry(region)
            is SyncRegion -> emitSync(region)
        }
    }

    private fun emitContainer(c: IrContainer) {
        when (c) {
            is BasicBlock -> emitStatements(c)
            is Region -> emitRegion(c)
            // IrContainer is BasicBlock | Region today; leave a visible marker rather than silently
            // dropping an unexpected future kind (no-silent-code-loss). Flag the method so the marker
            // is coupled to HAS_ERROR and error accounting can't undercount it.
            else -> {
                flagError(method, "unhandled container in region tree")
                code.add("/* unhandled container */").newLine()
            }
        }
    }

    private fun openBrace() {
        code.add("{").newLine()
        code.incIndent()
    }

    private fun closeBrace() {
        code.decIndent()
        code.add("}")
    }

    private fun emitIf(region: IfRegion) {
        code.add("if (")
        emitCondition(region.condition, Prec.LOWEST)
        code.add(") ")
        openBrace()
        emitRegion(region.thenRegion)
        closeBrace()
        val elseRegion = region.elseRegion
        if (elseRegion == null) {
            code.newLine()
            return
        }
        code.add(" else ")
        val elseIf = asSingleIf(elseRegion)
        if (elseIf != null) {
            emitIf(elseIf) // `else if` — the nested emitIf writes its own trailing newline
        } else {
            openBrace()
            emitRegion(elseRegion)
            closeBrace()
            code.newLine()
        }
    }

    /** If [region] is exactly one `IfRegion` (optionally wrapped in a single-child sequence), return it. */
    private fun asSingleIf(region: Region): IfRegion? = when (region) {
        is IfRegion -> region
        is SequenceRegion -> (region.children.singleOrNull() as? IfRegion)
        else -> null
    }

    private fun emitLoop(region: LoopRegion) {
        when (region.kind) {
            LoopKind.DO_WHILE -> {
                code.add("do ")
                openBrace()
                emitRegion(region.body)
                closeBrace()
                code.add(" while (")
                val cond = region.condition
                if (cond == null) code.add("true") else emitCondition(cond, Prec.LOWEST)
                code.add(");").newLine()
            }
            LoopKind.FOR -> emitForLoop(region)
            else -> {
                // WHILE, INFINITE, and (lacking iterable data) FOR_EACH all render as a while header.
                code.add("while (")
                val cond = region.condition
                if (cond == null) code.add("true") else emitCondition(cond, Prec.LOWEST)
                code.add(") ")
                openBrace()
                emitRegion(region.body)
                closeBrace()
                code.newLine()
            }
        }
    }

    private fun emitForLoop(region: LoopRegion) {
        code.add("for (")
        region[CodegenKeys.LOOP_INIT]?.let { emitStatementCore(it) }
        code.add("; ")
        region.condition?.let { emitCondition(it, Prec.LOWEST) }
        code.add("; ")
        region[CodegenKeys.LOOP_UPDATE]?.let { emitStatementCore(it) }
        code.add(") ")
        openBrace()
        emitRegion(region.body)
        closeBrace()
        code.newLine()
    }

    private fun emitSwitch(region: SwitchRegion) {
        code.add("switch (")
        emitOperand(region.selector, Prec.LOWEST)
        code.add(") ")
        openBrace()
        for (case in region.cases) {
            for (key in case.keys) {
                code.add("case ").add(key.toString()).add(":").newLine()
            }
            code.incIndent()
            emitRegion(case.body)
            code.decIndent()
        }
        region.defaultCase?.let { def ->
            code.add("default:").newLine()
            code.incIndent()
            emitRegion(def)
            code.decIndent()
        }
        closeBrace()
        code.newLine()
    }

    private fun emitTry(region: TryCatchRegion) {
        code.add("try ")
        openBrace()
        emitRegion(region.tryRegion)
        closeBrace()
        for (catch in region.catches) emitCatch(catch)
        region.finallyRegion?.let {
            code.add(" finally ")
            openBrace()
            emitRegion(it)
            closeBrace()
        }
        code.newLine()
    }

    private fun emitCatch(catch: CatchClause) {
        code.add(" catch (")
        val typeText = if (catch.exceptionTypes.isEmpty()) {
            types.render(IrType.THROWABLE)
        } else {
            catch.exceptionTypes.joinToString(" | ") { types.render(it) }
        }
        code.add(typeText).add(" ")
        val exVar = catch.exceptionVar as? RegisterOperand
        if (exVar != null) {
            val ref = varRefFor(exVar).also { declared.add(varKey(exVar)) }
            code.variable(ref, declaration = true)
        } else {
            code.add(names.unique("e"))
        }
        code.add(") ")
        openBrace()
        emitRegion(catch.body)
        closeBrace()
    }

    private fun emitSync(region: SyncRegion) {
        code.add("synchronized (")
        emitOperand(region.monitor, Prec.LOWEST)
        code.add(") ")
        openBrace()
        emitRegion(region.body)
        closeBrace()
        code.newLine()
    }

    // ---------- statements ----------

    private fun emitStatement(insn: Instruction) {
        if (insn.contains(AttrFlag.DONT_GENERATE)) return
        if (insn in suppressed) return
        // The <clinit>'s terminal fall-off `return;` is illegal inside a `static { … }` block (javac:
        // "return outside method"), so it is dropped — but ONLY that terminal instruction, never an early
        // conditional return-void (see [clinitTerminalReturn]).
        if (insn === clinitTerminalReturn) return
        when (insn.opcode) {
            IrOpcode.NOP, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
            IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT,
            // GOTO is pure control transfer (no data-flow); it becomes fallthrough in the linear form and
            // is carried by the region tree once structuring exists — never a source statement itself.
            IrOpcode.GOTO,
            -> return
            // A resolved `fill-array-data` expands into its own multi-line `arr[i] = v;` block (it is not a
            // single expression). A bare FILL_ARRAY without decoded data (no FillArrayInstruction) has no
            // elements to render and falls through to the honest bail.
            IrOpcode.FILL_ARRAY -> if (insn is FillArrayInstruction) {
                emitFillArray(insn)
            } else {
                if (emitStatementCore(insn)) code.add(";").newLine()
            }
            else -> {
                if (!emitStatementCore(insn)) return
                code.add(";").newLine()
            }
        }
    }

    /**
     * Emit the statement text for [insn] without the trailing `;`/newline (used inline in `for`
     * headers). Returns false if nothing was emitted.
     */
    private fun emitStatementCore(insn: Instruction): Boolean {
        when (insn.opcode) {
            IrOpcode.RETURN -> {
                code.add("return")
                if (insn.argCount > 0) {
                    code.add(" ")
                    // Coerce to the declared return type (a boolean returned as int, etc.).
                    emitCoerced(insn.getArg(0), method.returnType, Prec.LOWEST)
                }
            }
            IrOpcode.THROW -> {
                code.add("throw ")
                emitOperand(insn.getArg(0), Prec.LOWEST)
            }
            IrOpcode.BREAK -> code.add("break")
            IrOpcode.CONTINUE -> code.add("continue")
            IrOpcode.INSTANCE_PUT -> emitInstancePut(insn)
            IrOpcode.STATIC_PUT -> emitStaticPut(insn)
            IrOpcode.ARRAY_PUT -> {
                // Canonical IR order (jadx: aput): args = [value, array, index].
                val value = insn.getArg(0)
                val array = insn.getArg(1)
                val index = insn.getArg(2)
                emitOperand(array, Prec.PRIMARY)
                code.add("[")
                emitOperand(index, Prec.LOWEST)
                code.add("] = ")
                emitCoerced(value, operandType(array).arrayElement, Prec.LOWEST)
            }
            else -> {
                val result = insn.result
                if (result != null) {
                    emitAssignment(result, insn)
                } else {
                    // A bare expression statement (e.g. a void invoke).
                    emitInsnExpr(insn, Prec.LOWEST)
                }
            }
        }
        return true
    }

    private fun emitAssignment(result: RegisterOperand, insn: Instruction) {
        val ref = varRefFor(result)
        val key = varKey(result)
        // A block-local temp (defined+used only within one block, marked BLOCK_LOCAL_TEMP by the
        // structuring stage) emitted a SECOND time means its block was DUPLICATED into another region
        // position (a shared straight-line tail placed in each arm). The first copy's declaration is not
        // in scope in a sibling arm, so it must be re-declared locally in each copy. This fires ONLY on
        // re-emission (`key in declared`), so it is a no-op for any block emitted once. Cross-block /
        // coalesced values are never marked, so they keep their single dominating declaration.
        val redeclareDuplicate = key in declared && insn.contains(AttrFlag.BLOCK_LOCAL_TEMP)
        if (redeclareDuplicate || (key !in declared && !isPreDeclared(result))) {
            code.add(types.render(effectiveType(result))).add(" ")
            code.variable(ref, declaration = true)
            declared.add(key)
        } else {
            code.variable(ref, declaration = false)
        }
        code.add(" = ")
        emitInsnExpr(insn, Prec.LOWEST)
    }

    private fun emitInstancePut(insn: Instruction) {
        val field = (insn as? FieldInstruction)?.fieldRef
        emitOperand(insn.getArg(0), Prec.PRIMARY)
        code.add(".")
        emitFieldName(field)
        code.add(" = ")
        // For a put, the value is the last arg (instance put: [receiver, value]); coerce to field type.
        emitCoerced(insn.getArg(insn.argCount - 1), field?.type, Prec.LOWEST)
    }

    private fun emitStaticPut(insn: Instruction) {
        val field = (insn as? FieldInstruction)?.fieldRef
        // The qualifier is dropped ONLY for an assignment to the enclosing class's own blank `static
        // final` field. That case (a <clinit> initializer over a field whose declaration carries no
        // value) REQUIRES the simple name: javac rejects the qualified `Cls.FIELD = …` for a blank final
        // ("cannot assign a value to static final variable"). Everywhere else the field stays qualified —
        // for an assignable static `Cls.count = x` is legal AND avoids a same-named local shadowing the
        // simple name and silently capturing the assignment.
        if (field == null || !isOwnBlankFinal(field)) {
            if (field != null) {
                emitTypeRef(field.declaringType)
                code.add(".")
            }
        }
        emitFieldName(field)
        code.add(" = ")
        // For a static put, the value is the last (only) arg; coerce to field type.
        emitCoerced(insn.getArg(insn.argCount - 1), field?.type, Prec.LOWEST)
    }

    /**
     * True when [field] is the enclosing class's own `static final` field left WITHOUT a declaration
     * initializer (a blank final) — the only static put that must be written by simple name.
     */
    private fun isOwnBlankFinal(field: com.jadxmp.ir.insn.FieldRef): Boolean {
        if ((field.declaringType as? IrType.Object)?.className != method.declaringClass.fullName) return false
        val decl = method.declaringClass.fields.firstOrNull { it.name == field.name } ?: return false
        val staticFinal = JavaModifiers.STATIC or JavaModifiers.FINAL
        return decl.accessFlags and staticFinal == staticFinal && decl.constValue == null
    }

    private fun emitFieldName(field: com.jadxmp.ir.insn.FieldRef?) {
        if (field != null) {
            code.attachReference(FieldNodeRef(className(field.declaringType), field.name))
            // Reference display resolves to the referenced field's scope-unique alias (matching its
            // definition, including any deobfuscation/user override in [aliasMap]); the metadata ref above
            // keeps the binary name for jump-to-def identity.
            code.add(JavaMemberAliases.aliasForFieldRef(root, field, aliasMap))
        } else {
            // A field-opcode insn that is not a FieldInstruction carries no field name. The decoder always
            // builds the right subclass, so this is unreachable today — but fabricating a `field` identifier
            // would be a silent miscompile if the invariant ever broke. BAIL honestly instead (rule 4).
            code.emitErrorMarker(method, "field opcode without a FieldInstruction (no field name)")
        }
    }

    /** The loaded model, used to resolve a field/method reference to its (possibly renamed) alias. */
    private val root get() = method.declaringClass.root

    // ---------- expressions ----------

    private fun emitOperand(op: Operand, minPrec: Int) {
        when (op) {
            is RegisterOperand -> {
                val enumConstField = op.ssaValue?.let { enumConstantResultFields[it] }
                if (enumConstField != null) {
                    // A read of a suppressed enum-constant construction ⇒ the constant's own name.
                    emitFieldName(enumConstField)
                } else {
                    val def = if (inlineRegisters) inlinableDef(op) else null
                    if (def != null) emitInsnExpr(def, minPrec) else emitRegister(op)
                }
            }
            is LiteralOperand -> code.add(JavaLiterals.format(op))
            is InstructionOperand -> emitInsnExpr(op.instruction, minPrec)
        }
    }

    private fun emitRegister(reg: RegisterOperand) {
        if (isThis(reg)) {
            code.add("this")
            return
        }
        code.variable(varRefFor(reg), declaration = false)
    }

    private inline fun wrapped(prec: Int, minPrec: Int, body: () -> Unit) {
        val paren = prec < minPrec
        if (paren) code.add("(")
        body()
        if (paren) code.add(")")
    }

    private fun emitInsnExpr(insn: Instruction, minPrec: Int) {
        // Rule-4 F2 depth guard (see [MAX_EXPR_DEPTH]). Throwing past the cap lets the per-member backstop
        // convert the whole method to one honest marker instead of recursing into a StackOverflowError.
        // Increment/decrement are balanced on the normal path; a throw abandons this per-method writer, so
        // the decrement skipped by the exception below can never leak into another member.
        if (exprDepth >= MAX_EXPR_DEPTH) throw ExpressionTooDeepException()
        exprDepth++
        when (insn.opcode) {
            // A CONST always carries its literal operand and a CONST_STRING is always a ConstStringInstruction
            // (the decoder guarantees both), so the else/`?:` arms are unreachable today. Fabricating `0` / `""`
            // would silently miscompile a broken insn, so BAIL honestly instead (rule 4).
            IrOpcode.CONST ->
                if (insn.argCount > 0) emitOperand(insn.getArg(0), minPrec)
                else code.emitErrorMarker(method, "const without a literal operand")
            IrOpcode.CONST_STRING -> {
                val value = (insn as? ConstStringInstruction)?.value
                if (value != null) code.add(JavaLiterals.stringLiteral(value))
                else code.emitErrorMarker(method, "const-string without a ConstStringInstruction")
            }
            IrOpcode.CONST_CLASS -> {
                val t = referencedType(insn) ?: insn.result?.type ?: IrType.OBJECT
                emitTypeRef(t)
                code.add(".class")
            }
            IrOpcode.ARITH, IrOpcode.NEG -> emitArith(insn, minPrec)
            IrOpcode.NOT -> wrapped(Prec.UNARY, minPrec) {
                code.add("~")
                emitOperand(insn.getArg(0), Prec.UNARY)
            }
            IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG ->
                if (insn.argCount > 0) {
                    emitOperand(insn.getArg(0), minPrec)
                } else {
                    // A 0-arg move/one-arg has no source operand to forward: broken (uncompilable)
                    // placeholder output ⇒ flag the method so error accounting can't undercount it.
                    flagError(method, "empty move/one-arg")
                    code.add("/* empty */")
                }
            IrOpcode.CAST -> emitPrimitiveCast(insn, minPrec)
            IrOpcode.CHECK_CAST -> wrapped(Prec.UNARY, minPrec) {
                val t = referencedType(insn) ?: insn.result?.type ?: IrType.OBJECT
                code.add("(")
                emitTypeRef(t)
                code.add(") ")
                emitOperand(insn.getArg(0), Prec.UNARY)
            }
            IrOpcode.INSTANCE_OF -> wrapped(Prec.RELATIONAL, minPrec) {
                emitOperand(insn.getArg(0), Prec.RELATIONAL)
                code.add(" instanceof ")
                emitTypeRef(referencedType(insn) ?: IrType.OBJECT)
            }
            IrOpcode.CMP -> emitCompare(insn)
            IrOpcode.IF -> emitIfExpr(insn as IfInstruction, minPrec)
            IrOpcode.TERNARY -> wrapped(Prec.TERNARY, minPrec) {
                emitOperand(insn.getArg(0), Prec.TERNARY + 1)
                code.add(" ? ")
                emitOperand(insn.getArg(1), Prec.TERNARY)
                code.add(" : ")
                emitOperand(insn.getArg(2), Prec.TERNARY)
            }
            IrOpcode.STRING_CONCAT -> wrapped(Prec.ADD, minPrec) {
                if (insn.argCount == 0) {
                    code.add("\"\"")
                } else {
                    for (i in 0 until insn.argCount) {
                        if (i > 0) code.add(" + ")
                        emitOperand(insn.getArg(i), if (i == 0) Prec.ADD else Prec.ADD + 1)
                    }
                }
            }
            IrOpcode.NEW_INSTANCE -> {
                // A bare NEW_INSTANCE reaching expression codegen is ALWAYS a bug, never a legit no-arg
                // allocation: a real no-arg `new` is a CONSTRUCTOR insn (→ `new T()` via emitInvoke). This
                // path is only hit when ConstructorReconstruction could not fuse the `new-instance` with its
                // paired `<init>(args)` invoke (it chases only MOVE; bails on PHI/other defs), leaving an
                // orphan raw allocation. Rendering `new T()` here would SILENTLY drop the constructor args
                // (the leftover `<init>` renders its own spurious `new T(args)` separately) — precisely the
                // rule-4 silent miscompile. It is not faithfully renderable in codegen alone, so BAIL honestly.
                code.emitErrorMarker(method, "unfused new-instance / constructor not reconstructed")
            }
            IrOpcode.NEW_ARRAY -> emitNewArray(insn)
            IrOpcode.FILLED_NEW_ARRAY -> emitFilledNewArray(insn)
            IrOpcode.ARRAY_LENGTH -> {
                emitOperand(insn.getArg(0), Prec.PRIMARY)
                code.add(".length")
            }
            IrOpcode.ARRAY_GET -> {
                emitOperand(insn.getArg(0), Prec.PRIMARY)
                code.add("[")
                emitOperand(insn.getArg(1), Prec.LOWEST)
                code.add("]")
            }
            IrOpcode.INSTANCE_GET -> emitInstanceGet(insn)
            IrOpcode.STATIC_GET -> emitStaticGet(insn)
            IrOpcode.INVOKE, IrOpcode.CONSTRUCTOR -> emitInvokeExpr(insn, minPrec)
            else -> emitUnknownExpr(insn)
        }
        exprDepth--
    }

    private fun emitArith(insn: Instruction, minPrec: Int) {
        val op = (insn as? ArithInstruction)?.op
        if (op == null || op == ArithOp.NEGATE || insn.argCount < 2) {
            // Unary negation (or a NEG opcode without the arith subclass).
            wrapped(Prec.UNARY, minPrec) {
                code.add("-")
                val arg = insn.getArg(0)
                // Guard against a following '-' (nested negation / negative literal): `--x` would lex as
                // pre-decrement — a different program. Parenthesize so we emit `-(-x)` / `-(-5)`.
                if (operandStartsWithMinus(arg)) {
                    code.add("(")
                    emitOperand(arg, Prec.LOWEST)
                    code.add(")")
                } else {
                    emitOperand(arg, Prec.UNARY)
                }
            }
            return
        }
        val prec = op.precedence()
        // `&`/`|`/`^` on a boolean operand are the LOGICAL forms; a numeric-literal operand (`z ^ 1`)
        // must render as a boolean (`z ^ true`), else javac rejects `boolean ^ int`.
        val boolLogic = isBooleanLogic(insn)
        wrapped(prec, minPrec) {
            if (boolLogic) emitCoerced(insn.getArg(0), IrType.BOOLEAN, prec) else emitOperand(insn.getArg(0), prec)
            code.add(" ").add(op.symbol).add(" ")
            if (boolLogic) emitCoerced(insn.getArg(1), IrType.BOOLEAN, prec + 1) else emitOperand(insn.getArg(1), prec + 1)
        }
    }

    /**
     * True for a bitwise `&`/`|`/`^` whose operands are booleans (so it is really Java's logical
     * boolean operator). Detected when either operand's type is `boolean`, since the two operands of
     * these operators always share a type.
     */
    private fun isBooleanLogic(insn: Instruction): Boolean {
        val op = (insn as? ArithInstruction)?.op ?: return false
        if (op != ArithOp.AND && op != ArithOp.OR && op != ArithOp.XOR) return false
        if (insn.argCount < 2) return false
        return operandType(insn.getArg(0)).isBooleanPrimitive() || operandType(insn.getArg(1)).isBooleanPrimitive()
    }

    /** Whether emitting [op] would begin with a `-` token (a negative literal or a nested negation). */
    private fun operandStartsWithMinus(op: Operand): Boolean = when (op) {
        is LiteralOperand -> JavaLiterals.format(op).startsWith("-")
        is InstructionOperand -> instructionStartsWithMinus(op.instruction)
        is RegisterOperand -> false
    }

    private fun instructionStartsWithMinus(insn: Instruction): Boolean = when (insn.opcode) {
        IrOpcode.NEG -> true
        IrOpcode.ARITH -> (insn as? ArithInstruction)?.op == ArithOp.NEGATE
        // Pass-through wrappers render their single argument directly.
        IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG, IrOpcode.CONST ->
            insn.argCount > 0 && operandStartsWithMinus(insn.getArg(0))
        else -> false
    }

    private fun emitPrimitiveCast(insn: Instruction, minPrec: Int) {
        val target = insn.result?.type ?: IrType.INT
        val arg = insn.getArg(0)
        // A boolean↔numeric cast needs the conditional coercion; anything else is a plain `(T)` cast.
        if (needsBooleanCoercion(operandType(arg), target)) {
            emitCoerced(arg, target, minPrec)
            return
        }
        wrapped(Prec.UNARY, minPrec) {
            code.add("(").add(types.render(target)).add(") ")
            emitOperand(arg, Prec.UNARY)
        }
    }

    /**
     * Emit [op] coerced to [targetType] where DEX left a boolean↔numeric conversion implicit — a
     * `boolean` used where a numeric primitive is expected becomes `cond ? (T) 1 : (T) 0`, and a
     * numeric used where a boolean is expected becomes `value != 0`. A plain `(byte) z` on a boolean is
     * not legal Java ("boolean cannot be converted to byte"), so this is what keeps output compilable
     * (matching jadx). Every other case emits [op] unchanged.
     */
    private fun emitCoerced(op: Operand, targetType: IrType?, minPrec: Int) {
        if (targetType != null) {
            val source = operandType(op)
            // A register that is PROVABLY a compile-time null constant (its sole def is `const 0` in a
            // reference slot), but whose out-of-SSA-coalesced local carries a type that is definitely
            // incompatible with the required [targetType] (e.g. an `InputStream` local reused to hold the
            // null returned as `byte[]`). Rendering the variable name would be a type error, so emit the
            // `null` literal instead — exactly equivalent, since the register holds null on this path.
            if (targetType.isReferenceType() && isNullConstRegister(op) &&
                definitelyIncompatibleReference(source, targetType)
            ) {
                code.add("null")
                return
            }
            if (source.isBooleanPrimitive() && targetType.isNumericPrimitive()) {
                emitBooleanToNumber(op, targetType, minPrec)
                return
            }
            if (targetType.isBooleanPrimitive()) {
                // A boolean context. A constant renders as the boolean literal `true`/`false` (this also
                // covers `return 0;` in a boolean method, where the value is boolean-typed but backed by
                // a NARROW `0` literal that would otherwise print as `0`). A resolved numeric value
                // becomes `value != 0`. An already-boolean or unresolved non-constant is emitted as-is.
                val constant = constantValueOf(op)
                when {
                    constant != null -> {
                        code.add(if (constant != 0L) "true" else "false")
                        return
                    }
                    source.isNumericPrimitive() -> {
                        emitNumberToBoolean(op, minPrec)
                        return
                    }
                }
            }
        }
        emitOperand(op, minPrec)
    }

    /** The constant [Long] value if [op] is a literal (or a `CONST` wrapping one), else null. */
    private fun constantValueOf(op: Operand): Long? = when (op) {
        is LiteralOperand -> op.value
        is InstructionOperand -> {
            val i = op.instruction
            if (i.opcode == IrOpcode.CONST && i.argCount > 0) (i.getArg(0) as? LiteralOperand)?.value else null
        }
        else -> null
    }

    private fun needsBooleanCoercion(source: IrType, target: IrType): Boolean =
        (source.isBooleanPrimitive() && target.isNumericPrimitive()) ||
            (target.isBooleanPrimitive() && source.isNumericPrimitive())

    private fun emitBooleanToNumber(cond: Operand, target: IrType, minPrec: Int) {
        wrapped(Prec.TERNARY, minPrec) {
            emitOperand(cond, Prec.TERNARY + 1)
            if ((target as? IrType.Primitive)?.kind == TypeKind.INT) {
                // `1`/`0` are already int; no cast needed (and matches jadx's `z ? 1 : 0`).
                code.add(" ? 1 : 0")
            } else {
                val t = types.render(target)
                code.add(" ? (").add(t).add(") 1 : (").add(t).add(") 0")
            }
        }
    }

    private fun emitNumberToBoolean(value: Operand, minPrec: Int) {
        wrapped(Prec.EQUALITY, minPrec) {
            emitOperand(value, Prec.EQUALITY)
            code.add(" != 0")
        }
    }

    private fun operandType(op: Operand): IrType = when (op) {
        is RegisterOperand -> effectiveType(op)
        // A wrapped boolean-logic op (`z ^ 1`) yields a boolean, even if its result register was left
        // int-typed — reflect that so an enclosing boolean coercion does not wrap it in `!= 0`.
        is InstructionOperand -> if (isBooleanLogic(op.instruction)) IrType.BOOLEAN else op.type
        else -> op.type
    }

    private fun IrType.isBooleanPrimitive(): Boolean = this is IrType.Primitive && kind == TypeKind.BOOLEAN

    /** A resolved reference type (a `null` literal can be cast to it). */
    private fun IrType.isReferenceType(): Boolean =
        this is IrType.Object || this is IrType.ArrayType || this is IrType.TypeVariable || this is IrType.Wildcard

    /**
     * True if [op] is a register whose single SSA definition is a `const 0` — i.e. it provably holds
     * the null constant on the read at hand. (A `const 0` reaching a reference-typed use is `null`.)
     * Per-SSA-version: a register reassigned before this use points at a different SSA value whose def
     * is that reassignment, so this correctly returns false for it.
     */
    private fun isNullConstRegister(op: Operand): Boolean {
        if (op !is RegisterOperand || isThis(op)) return false
        val def = op.ssaValue?.assign?.parent ?: return false
        return def.opcode == IrOpcode.CONST && def.argCount > 0 &&
            (def.getArg(0) as? LiteralOperand)?.value == 0L
    }

    /**
     * True when [source] and [target] are reference types that are DEFINITELY not assignable in either
     * direction — restricted to the cases codegen can decide without a class graph: an array vs a named
     * non-array class (only `Object`/`Serializable`/`Cloneable` may hold an array), or two primitive-
     * element arrays with different element kinds. Conservative: when a subtype relation is possible
     * (two named classes, covariant reference arrays), it returns false so the variable is kept as-is.
     */
    private fun definitelyIncompatibleReference(source: IrType, target: IrType): Boolean {
        val sArr = source is IrType.ArrayType
        val tArr = target is IrType.ArrayType
        if (sArr != tArr) {
            val objSide = if (sArr) target else source
            return objSide is IrType.Object && objSide.className !in ARRAY_SUPERTYPE_CLASSES
        }
        if (sArr && tArr) {
            val se = (source as IrType.ArrayType).element
            val te = (target as IrType.ArrayType).element
            if (se is IrType.Primitive || te is IrType.Primitive) return se != te
        }
        return false
    }

    /** True if [op] renders as the `null` literal (a zero constant of reference/unknown-reference type). */
    private fun isNullConstant(op: Operand): Boolean {
        if (constantValueOf(op) != 0L) return false
        val t = operandType(op)
        return when (t) {
            is IrType.Object, is IrType.ArrayType, is IrType.TypeVariable, is IrType.Wildcard -> true
            is IrType.Unknown -> t.possible.all { it == TypeKind.OBJECT || it == TypeKind.ARRAY }
            else -> false
        }
    }

    private fun IrType.isNumericPrimitive(): Boolean =
        this is IrType.Primitive && kind != TypeKind.BOOLEAN && kind != TypeKind.VOID

    private fun emitCompare(insn: Instruction) {
        // CMP produces -1/0/1; standalone it is rare (structuring folds it into an IF). Render via the
        // matching boxed comparator so the fallback still compiles.
        val a = insn.getArg(0)
        val cls = when ((a.type as? IrType.Primitive)?.kind) {
            com.jadxmp.ir.type.TypeKind.LONG -> "Long"
            com.jadxmp.ir.type.TypeKind.FLOAT -> "Float"
            com.jadxmp.ir.type.TypeKind.DOUBLE -> "Double"
            else -> "Integer"
        }
        code.add(cls).add(".compare(")
        emitOperand(a, Prec.LOWEST)
        code.add(", ")
        emitOperand(insn.getArg(1), Prec.LOWEST)
        code.add(")")
    }

    private fun emitIfExpr(insn: IfInstruction, minPrec: Int) {
        val prec = insn.condition.precedence()
        wrapped(prec, minPrec) {
            emitOperand(insn.getArg(0), prec)
            code.add(" ").add(insn.condition.symbol).add(" ")
            // A one-operand IF is an implicit compare-to-zero (jadx: IF_EQZ/…); render the `0`.
            if (insn.argCount > 1) emitOperand(insn.getArg(1), prec + 1) else code.add("0")
        }
    }

    private fun emitNewArray(insn: Instruction) {
        // referencedType is the WHOLE array type (contract). A sized new-array binds the size to the
        // OUTER dimension, so it must render `new <root>[size][]…` — e.g. int[][] -> `new int[n][]`,
        // never `new int[][n]`. Peel to the innermost element and re-append the remaining dims empty.
        val arrayType = referencedType(insn) ?: insn.result?.type
        val root = arrayType?.arrayRootElement ?: IrType.INT
        val dimensions = arrayType?.arrayDimension ?: 1
        code.add("new ")
        emitTypeRef(root)
        code.add("[")
        if (insn.argCount > 0) emitOperand(insn.getArg(0), Prec.LOWEST)
        code.add("]")
        repeat((dimensions - 1).coerceAtLeast(0)) { code.add("[]") }
    }

    private fun emitFilledNewArray(insn: Instruction) {
        val element = arrayElementFor(insn) ?: IrType.OBJECT
        code.add("new ")
        emitTypeRef(element)
        code.add("[]{")
        for (i in 0 until insn.argCount) {
            if (i > 0) code.add(", ")
            emitOperand(insn.getArg(i), Prec.LOWEST)
        }
        code.add("}")
    }

    /**
     * jadx: InsnGen.fillArray — a `fill-array-data` renders as a `// fill-array-data instruction` comment
     * followed by one `arr[i] = value;` assignment per decoded element. Each element literal is formatted
     * with the array's element type (below), so byte/short/int/long/char/float/double all render correctly
     * and signed.
     */
    private fun emitFillArray(insn: FillArrayInstruction) {
        val elemType = fillArrayElementType(insn)
        code.add("// fill-array-data instruction").newLine()
        for (i in 0 until insn.size) {
            emitOperand(insn.array, Prec.PRIMARY)
            code.add("[").add(i.toString()).add("] = ")
            code.add(JavaLiterals.format(LiteralOperand(insn.elements[i], elemType)))
            code.add(";").newLine()
        }
    }

    /**
     * The element type used to format each `fill-array-data` literal. Prefer the array operand's inferred
     * element type when it is a known primitive; otherwise default from the element width (jadx:
     * `elementType.selectFirst()` — byte / short / int / long for width 1 / 2 / 4 / 8).
     */
    private fun fillArrayElementType(insn: FillArrayInstruction): IrType {
        val elem = operandType(insn.array).arrayElement
        // Divergence from jadx (harmless): jadx only checks `isArray()`, we additionally require a known
        // PRIMITIVE element. `fill-array-data` targets only primitive arrays, so a non-primitive element
        // type is spurious — falling back to the width default is the safe choice either way.
        if (elem != null && elem.isTypeKnown && elem is IrType.Primitive) return elem
        return when (insn.elementWidth) {
            1 -> IrType.BYTE
            2 -> IrType.SHORT
            4 -> IrType.INT
            else -> IrType.LONG
        }
    }

    /** The element type for a `new T[]`, taken from the referenced (whole) array type or the result type. */
    private fun arrayElementFor(insn: Instruction): IrType? {
        val arrayType = referencedType(insn) ?: insn.result?.type
        return arrayType?.arrayElement ?: (arrayType as? IrType.ArrayType)?.element
    }

    private fun referencedType(insn: Instruction): IrType? = (insn as? TypeInstruction)?.referencedType

    private fun emitInstanceGet(insn: Instruction) {
        val field = (insn as? FieldInstruction)?.fieldRef
        emitOperand(insn.getArg(0), Prec.PRIMARY)
        code.add(".")
        emitFieldName(field)
    }

    private fun emitStaticGet(insn: Instruction) {
        val field = (insn as? FieldInstruction)?.fieldRef
        // Obfuscated-enum rewrite: a read of the hidden `$VALUES` backing array has no field to name, so
        // it renders as a call to the compiler-regenerated `values()` (jadx: EnumVisitor). The array and
        // `values()` agree in length/contents, so `$VALUES.length` etc. stays faithful.
        if (field != null && enumRewrites != null &&
            (field.declaringType as? IrType.Object)?.className == enumRewrites.enumClassName &&
            field.name == enumRewrites.valuesFieldName
        ) {
            code.add("values()")
            return
        }
        if (field != null) {
            emitTypeRef(field.declaringType)
            code.add(".")
        }
        emitFieldName(field)
    }

    /**
     * Emit an invoke expression, inserting a downcast when the value's inferred type was narrowed below
     * the call's erased `java.lang.Object` return type. DEX lets an `invoke-{interface,virtual}`
     * dispatch on an erased `Object` value with no preceding `check-cast` (e.g. `Iterator.next()` used
     * directly as a `Map.Entry` receiver — types/TestGenerics2); type inference recovers the real type
     * from that receiver use, but the *Java* expression `it.next()` is still statically `Object`, so
     * assigning it to — or using it at — the narrower type needs an explicit `(T)` cast to compile
     * (this is what jadx emits). The cast is always safe: the narrowing was proven from a
     * bytecode-guaranteed receiver, so it can never throw. Fires ONLY when the declared return is the
     * root `Object` and the inferred result is a strictly narrower reference type — exactly the cases
     * type inference can now recover — so every other call renders unchanged.
     */
    private fun emitInvokeExpr(insn: Instruction, minPrec: Int) {
        val ret = (insn as? InvokeInstruction)?.methodRef?.returnType
        val resultType = insn.result?.type
        if (ret == IrType.OBJECT && resultType != null &&
            resultType.isReferenceType() && resultType != IrType.OBJECT
        ) {
            wrapped(Prec.UNARY, minPrec) {
                code.add("(")
                emitTypeRef(resultType)
                code.add(") ")
                emitInvoke(insn, Prec.UNARY)
            }
            return
        }
        emitInvoke(insn, minPrec)
    }

    private fun emitInvoke(insn: Instruction, minPrec: Int) {
        // A raw invoke-custom (invokedynamic) has its own polymorphic-style rendering.
        if (insn is InvokeCustomInstruction) {
            emitInvokeCustom(insn, minPrec)
            return
        }
        // An unresolved invoke arrives as a bare Instruction(INVOKE), not an InvokeInstruction —
        // keep the data-flow visible with a marker rather than guessing.
        val invoke = insn as? InvokeInstruction
        if (invoke == null) {
            emitUnknownExpr(insn)
            return
        }
        val method = invoke.methodRef
        val kind = invoke.invokeKind

        // Obfuscated-enum rewrite: a call to a hidden synthetic helper is redirected to the
        // compiler-regenerated `values()`/`valueOf(...)` (the obfuscated originals were hidden).
        if (emitRewrittenEnumInvoke(invoke, method, kind)) return

        // A normalized constructor renders `new T(args)`; the new-instance is the result, not an arg.
        if (invoke.opcode == IrOpcode.CONSTRUCTOR) {
            code.add("new ")
            emitTypeRef(method.declaringType)
            emitArgList(invoke, 0, method.paramTypes)
            return
        }
        // An un-normalized `<init>` invoke. Two very different things share `invoke-direct <init>`:
        //  - a constructor *delegation* whose receiver is the object under construction (`this`) — this
        //    is `this(...)` (same class) or `super(...)` (superclass);
        //  - a constructor *call* on some other object (a freshly `new`ed one) — this is `new T(args)`.
        // The receiver tells them apart; keying only off `isConstructor` (the old bug) wrongly rendered a
        // `new FileNotFoundException("")` as an illegal `super("")` in the middle of a method.
        if (method.isConstructor) {
            val receiver = invoke.instanceArg
            var firstArg = if (invoke.hasInstance) 1 else 0
            var paramTypes = method.paramTypes
            if (receiver is RegisterOperand && isThis(receiver)) {
                // DEX has no `invoke-super` for constructors, so decide this()/super() by target class.
                val enclosingName = this.method.declaringClass.fullName
                val targetName = (method.declaringType as? IrType.Object)?.className
                val sameClass = targetName == enclosingName
                code.add(if (sameClass) "this" else "super")
                // A same-class `this(...)` delegation inside a reconstructed enum forwards the synthetic
                // leading `name`/`ordinal` the enclosing enum ctor received; the sibling ctor's declaration
                // has those two params stripped (emitEnumConstructor), so strip them from the forwarded
                // args too — else the arg list wouldn't line up with the stripped signature (and would even
                // reference the now-removed name/ordinal locals).
                if (sameClass && enclosingIsReconstructedEnum()) {
                    val skip = EnumReconstruction.syntheticArgCount(paramTypes.size)
                    firstArg += skip
                    paramTypes = paramTypes.drop(skip)
                }
            } else {
                // Constructor call on another object (usually an inlined `new`): render `new T(args)`.
                code.add("new ")
                emitTypeRef(method.declaringType)
            }
            emitArgList(invoke, firstArg, paramTypes)
            return
        }

        // Static-invoke / instance-declaration MISMATCH: an `invoke-static` whose target resolves to a
        // NON-static method of THIS same class (e.g. jadx left a `this`-free helper as an instance method
        // yet the bytecode calls it statically). javac rejects the `Class.method(...)` a static invoke would
        // normally render here ("non-static method … cannot be referenced from a static context"). Render it
        // instead as an UNQUALIFIED implicit-`this` call — which compiles and is behavior-preserving: the
        // callee was invoked with no receiver, so it cannot depend on `this`, and `invoke-static` carries no
        // receiver operand, so the argument list is byte-identical to the static form (offset 0). Matches
        // jadx's emission. Fires ONLY on this exact mismatch; every consistent call is untouched below.
        val staticInstanceMismatch =
            kind == InvokeKind.STATIC && resolvesToSameClassInstanceMethod(method)
        when {
            staticInstanceMismatch -> {} // unqualified: no receiver, no class qualifier
            kind == InvokeKind.STATIC -> emitTypeRef(method.declaringType)
            kind == InvokeKind.SUPER -> code.add("super")
            else -> {
                val receiver = invoke.instanceArg
                if (receiver != null) emitOperand(receiver, Prec.PRIMARY) else code.add("this")
            }
        }
        if (!staticInstanceMismatch) code.add(".")
        code.attachReference(MethodNodeRef(className(method.declaringType), method.name, method.paramTypes.map { it.toString() }))
        code.add(methodCallName(method))
        // Instance forms carry the receiver as arg 0; skip it when listing the actual arguments.
        emitArgList(invoke, if (kind == InvokeKind.STATIC) 0 else 1, method.paramTypes)
    }

    /**
     * True when [ref] is an `invoke-static` target that actually resolves to a **non-static** method of
     * the ENCLOSING class — the static-invoke / instance-declaration mismatch that must render as an
     * unqualified implicit-`this` call rather than `Class.method(…)`.
     *
     * Two firing preconditions keep the qualifier-drop provably behavior-preserving for EVERY admitted
     * case (rule 4), not just the corpus-observed one:
     *  - **The ENCLOSING method must be non-static.** An unqualified instance call needs an implicit `this`
     *    in scope; inside a `static` method or `<clinit>` there is none, so dropping the qualifier would
     *    either be rejected by javac or silently rebind to a widening static overload (a silent miscompile).
     *  - **The resolved declaration must match name + declared params + RETURN type.** The JVM (and
     *    obfuscator output, which this corpus is derived from) permits two same-class methods that differ
     *    only in return type; matching on name+params alone could pick the instance sibling of a genuinely
     *    static target via insertion order and wrongly drop a needed qualifier. `argTypes`/`paramTypes`
     *    both exclude the implicit `this`.
     * Returns false when the target is another class, is a genuine static method, or is not present in the
     * class model — so consistent calls fall through to the normal qualifier rules unchanged.
     */
    private fun resolvesToSameClassInstanceMethod(ref: MethodRef): Boolean {
        if (method.isStatic) return false
        if ((ref.declaringType as? IrType.Object)?.className != method.declaringClass.fullName) return false
        val decl = method.declaringClass.methods.firstOrNull {
            it.name == ref.name && it.argTypes == ref.paramTypes && it.returnType == ref.returnType
        } ?: return false
        return !decl.isStatic
    }

    /**
     * Render a raw `invoke-custom` as jadx does — a polymorphic-style call through the resolved
     * bootstrap:
     * `(<Ret>) bootstrap(MethodHandles.lookup(), "<name>",
     * MethodType.methodType(<Ret>.class, <P>.TYPE, …)).dynamicInvoker().invoke(<args>) /* invoke-custom */`.
     * A shape we cannot spell faithfully (marked non-renderable in decode) bails to a marker (rule 4).
     */
    private fun emitInvokeCustom(insn: InvokeCustomInstruction, minPrec: Int) {
        if (!insn.renderable) {
            code.emitErrorMarker(method, "unsupported invoke-custom (field/non-static handle or extra bootstrap args)")
            return
        }
        val returnType = insn.protoReturnType
        val body = {
            if (returnType != IrType.VOID) {
                code.add("(")
                emitTypeRef(returnType)
                code.add(") ")
            }
            emitBootstrapCall(insn)
            code.add(".dynamicInvoker().invoke")
            // The runtime register args, coerced to the proto parameter types (as jadx does).
            emitArgList(insn, 0, insn.protoParamTypes)
            code.add(" /* invoke-custom */")
        }
        // A leading cast makes the whole thing a unary-precedence expression; without it, the postfix
        // call chain binds tighter than any operator, so no extra parentheses are needed.
        if (returnType != IrType.VOID) wrapped(Prec.UNARY, minPrec) { body() } else body()
    }

    /**
     * The bootstrap invocation with its synthesized compile-time arguments:
     * `bootstrap(MethodHandles.lookup(), "<name>", MethodType.methodType(<Ret>.class, <P>.TYPE, …))`.
     * A same-class static bootstrap omits the class qualifier (matches jadx).
     */
    private fun emitBootstrapCall(insn: InvokeCustomInstruction) {
        val boot = insn.bootstrapMethod
        val enclosing = method.declaringClass.fullName
        if ((boot.declaringType as? IrType.Object)?.className != enclosing) {
            emitTypeRef(boot.declaringType)
            code.add(".")
        }
        code.attachReference(MethodNodeRef(className(boot.declaringType), boot.name, boot.paramTypes.map { it.toString() }))
        code.add(methodCallName(boot))
        code.add("(")
        emitTypeRef(METHOD_HANDLES_TYPE)
        code.add(".lookup(), ")
        code.add(JavaLiterals.stringLiteral(insn.callSiteName))
        code.add(", ")
        emitMethodType(insn.protoReturnType, insn.protoParamTypes)
        code.add(")")
    }

    /** `MethodType.methodType(<Ret token>, <param tokens…>)` — the call-site signature literal. */
    private fun emitMethodType(returnType: IrType, paramTypes: List<IrType>) {
        emitTypeRef(METHOD_TYPE_TYPE)
        code.add(".methodType(")
        emitTypeToken(returnType)
        for (p in paramTypes) {
            code.add(", ")
            emitTypeToken(p)
        }
        code.add(")")
    }

    /** A `Class` token as jadx spells it: a primitive as `<Boxed>.TYPE`, a reference as `<Type>.class`. */
    private fun emitTypeToken(type: IrType) {
        if (type is IrType.Primitive) {
            emitTypeRef(boxedType(type.kind))
            code.add(".TYPE")
        } else {
            emitTypeRef(type)
            code.add(".class")
        }
    }

    /**
     * Emit a rewritten call to an obfuscated enum's hidden synthetic helper, returning true if handled.
     * A static call to the hidden `values()` clone becomes `values()`; a call to the hidden
     * `valueOf(String)` becomes `valueOf(args)` — both target the compiler-regenerated helper.
     */
    private fun emitRewrittenEnumInvoke(invoke: InvokeInstruction, method: com.jadxmp.ir.insn.MethodRef, kind: InvokeKind): Boolean {
        val rw = enumRewrites ?: return false
        if (kind != InvokeKind.STATIC) return false
        if ((method.declaringType as? IrType.Object)?.className != rw.enumClassName) return false
        if (rw.cloneMethodName != null && method.name == rw.cloneMethodName && method.paramTypes.isEmpty()) {
            code.add("values()")
            return true
        }
        // Match the EXACT synthetic `valueOf(String) -> EnumType` signature, not the name alone: a user
        // overload sharing the obfuscated name (e.g. `vo(int)`) must NOT be rewritten to `valueOf(...)`.
        if (rw.valueOfMethodName != null && method.name == rw.valueOfMethodName &&
            method.paramTypes.size == 1 &&
            (method.paramTypes[0] as? IrType.Object)?.className == "java.lang.String" &&
            (method.returnType as? IrType.Object)?.className == rw.enumClassName
        ) {
            code.add("valueOf")
            emitArgList(invoke, 0, method.paramTypes)
            return true
        }
        return false
    }

    /** The identifier a call renders: a renamed-user-method's new name, else the resolved alias. */
    private fun methodCallName(method: com.jadxmp.ir.insn.MethodRef): String {
        val ownerName = (method.declaringType as? IrType.Object)?.className
        val rw = enumRewrites
        if (rw != null && ownerName == rw.enumClassName) {
            // Same enum: consult only its precomputed rename map (fast path).
            rw.methodRenames[EnumRefRewrites.signatureKey(method.name, method.paramTypes)]?.let { return it }
        } else {
            // A call into ANOTHER class: if the target is a reconstructable enum whose reserved-signature
            // user method was renamed, spell the new name — otherwise the call would silently resolve to
            // the compiler-regenerated `values()`/`valueOf()` (a wrong target that still compiles).
            crossClassEnumRename(method, ownerName)?.let { return it }
        }
        return JavaMemberAliases.aliasForMethodRef(root, method, aliasMap)
    }

    /**
     * The rename for a cross-class call to [ref] whose owner is a reconstructable enum that renamed a
     * `values()`/`valueOf(String)`-signature user method — or null. Cheap-gated: only reserved-signature
     * calls are considered, and the (heavier) full reconstruction check runs only once a rename is
     * actually planned. Pure (read-only over the shared model), so it is safe on the parallel render path
     * and always agrees with the owning class's definition site.
     */
    private fun crossClassEnumRename(ref: com.jadxmp.ir.insn.MethodRef, ownerName: String?): String? {
        ownerName ?: return null
        val reserved = (ref.name == "values" && ref.paramTypes.isEmpty()) ||
            (ref.name == "valueOf" && ref.paramTypes.size == 1)
        if (!reserved) return null
        val cls = root.findClass(ownerName) ?: return null
        val target = cls.methods.firstOrNull { it.name == ref.name && it.argTypes == ref.paramTypes } ?: return null
        val planned = EnumReconstruction.plannedMemberRenames(cls)[target] ?: return null
        // Honor the rename only when the class actually reconstructs as an enum (else its definition kept
        // the original name); this mirrors the definition-site gate so both sides always agree.
        if (EnumReconstruction.analyze(cls) == null) return null
        return planned
    }

    private fun emitArgList(insn: Instruction, firstArgIndex: Int, paramTypes: List<IrType> = emptyList()) {
        code.add("(")
        var emitted = 0
        for (i in firstArgIndex until insn.argCount) {
            if (emitted > 0) code.add(", ")
            val arg = insn.getArg(i)
            val target = paramTypes.getOrNull(emitted)
            // A bare `null` argument is ambiguous when the method is overloaded (`f(long[])` vs
            // `f(short[])`); cast it to the declared reference parameter type to pin the overload (jadx
            // does the same). Otherwise coerce for boolean↔numeric mismatches.
            if (target != null && target.isReferenceType() && isNullConstant(arg)) {
                wrapped(Prec.UNARY, Prec.LOWEST) {
                    code.add("(")
                    emitTypeRef(target)
                    code.add(") null")
                }
            } else {
                emitCoerced(arg, target, Prec.LOWEST)
            }
            emitted++
        }
        code.add(")")
    }

    private fun emitUnknownExpr(insn: Instruction) {
        // No silent code loss: keep the operand and flag the unhandled opcode in a comment. This can fire
        // in an otherwise-linear (RenderabilityGuard-"renderable") method, so the HAS_ERROR flag would NOT
        // be set upstream — flag it here or countErrors/reportedErrors silently undercount broken output.
        flagError(method, "unhandled opcode: ${insn.opcode.name}")
        if (insn.argCount > 0) {
            emitOperand(insn.getArg(0), Prec.PRIMARY)
        } else {
            code.add("null")
        }
        code.add(" /* ").add(insn.opcode.name).add(" */")
    }

    private fun emitTypeRef(type: IrType) {
        val cls = classNameForRef(type)
        if (cls != null) code.attachReference(com.jadxmp.codegen.ClassNodeRef(cls))
        code.add(types.render(type))
    }

    // ---------- conditions ----------

    private fun emitCondition(cond: Condition, minPrec: Int) {
        when (cond) {
            is Condition.Compare -> {
                val prec = cond.op.precedence()
                wrapped(prec, minPrec) {
                    emitOperand(cond.left, prec)
                    code.add(" ").add(cond.op.symbol).add(" ")
                    emitOperand(cond.right, prec + 1)
                }
            }
            is Condition.BoolTest -> emitOperand(cond.operand, minPrec)
            is Condition.Not -> emitNot(cond.negated, minPrec)
            is Condition.And -> emitJunction(cond.terms, "&&", Prec.LOGIC_AND, minPrec)
            is Condition.Or -> emitJunction(cond.terms, "||", Prec.LOGIC_OR, minPrec)
        }
    }

    private fun emitNot(inner: Condition, minPrec: Int) {
        when (inner) {
            is Condition.Compare -> emitCondition(Condition.Compare(inner.op.negate(), inner.left, inner.right), minPrec)
            is Condition.Not -> emitCondition(inner.negated, minPrec)
            is Condition.BoolTest -> wrapped(Prec.UNARY, minPrec) {
                code.add("!")
                emitOperand(inner.operand, Prec.UNARY)
            }
            else -> wrapped(Prec.UNARY, minPrec) {
                code.add("!(")
                emitCondition(inner, Prec.LOWEST)
                code.add(")")
            }
        }
    }

    private fun emitJunction(terms: List<Condition>, symbol: String, prec: Int, minPrec: Int) {
        wrapped(prec, minPrec) {
            for ((i, term) in terms.withIndex()) {
                if (i > 0) code.add(" ").add(symbol).add(" ")
                emitCondition(term, prec)
            }
        }
    }

    // ---------- enum-constant arguments ----------

    /**
     * Render an enum constant's constructor argument list `(a, b, c)` — the real args after the
     * synthetic leading `name`/`ordinal` have been stripped by the caller. Registers are resolved to
     * their (inlined) defining expressions because these render at the enum-entry position, outside the
     * `<clinit>` variable scope where the source registers live. Args are coerced to [paramTypes] (the
     * matching declared constructor params) so a bare `null` pins the right overload, as jadx does.
     *
     * Returns false (rendering nothing) when any argument cannot be fully inlined to a self-contained
     * expression — the caller then flags the honest error rather than emit an undefined variable name.
     */
    fun emitEnumConstantArgs(args: List<Operand>, paramTypes: List<IrType>): Boolean {
        if (args.isEmpty()) return true
        if (args.any { !canInline(it, HashSet()) }) return false
        inlineRegisters = true
        code.add("(")
        for ((i, arg) in args.withIndex()) {
            if (i > 0) code.add(", ")
            val target = paramTypes.getOrNull(i)
            if (target != null && target.isReferenceType() && isNullConstant(arg)) {
                code.add("(")
                emitTypeRef(target)
                code.add(") null")
            } else {
                emitCoerced(arg, target, Prec.LOWEST)
            }
        }
        code.add(")")
        inlineRegisters = false
        return true
    }

    /**
     * Render an enum constant's constructor argument list from a resolved [EnumArg] plan (built by
     * [EnumReconstruction]). Each argument is one of: a folded `new T[]{…}` array literal (from a
     * `NEW_ARRAY`+dense-`ARRAY_PUT` run), a backward inter-constant NAME reference, or an inlined
     * expression tree — the last coerced to its declared [paramTypes] (a bare `null` pins the overload).
     * The plan's support instructions have already been suppressed from the residual `<clinit>`, so this
     * never leaves a dangling statement. Always succeeds (the plan is only built when fully renderable).
     */
    fun emitEnumConstantArgsPlanned(rendered: List<EnumArg>, paramTypes: List<IrType>): Boolean {
        if (rendered.isEmpty()) return true
        inlineRegisters = true
        code.add("(")
        for ((i, arg) in rendered.withIndex()) {
            if (i > 0) code.add(", ")
            when (arg) {
                is EnumArg.ConstantRef -> {
                    // Display the referenced constant's NAME; point jump-to-def at its backing field (the
                    // same FieldNodeRef the constant's own declaration attaches), so the reference resolves.
                    arg.field?.let { code.attachReference(FieldNodeRef(method.declaringClass.fullName, it.name)) }
                    code.add(arg.name)
                }
                is EnumArg.ArrayLiteral -> {
                    code.add("new ")
                    emitTypeRef(arg.elementType)
                    code.add("[]{")
                    for ((j, el) in arg.elements.withIndex()) {
                        if (j > 0) code.add(", ")
                        emitCoerced(el, arg.elementType, Prec.LOWEST)
                    }
                    code.add("}")
                }
                is EnumArg.Inline -> {
                    val target = paramTypes.getOrNull(i)
                    if (target != null && target.isReferenceType() && isNullConstant(arg.op)) {
                        code.add("(")
                        emitTypeRef(target)
                        code.add(") null")
                    } else {
                        emitCoerced(arg.op, target, Prec.LOWEST)
                    }
                }
            }
        }
        code.add(")")
        inlineRegisters = false
        return true
    }

    /** The defining instruction to inline for [reg], or null if it isn't a pure inlinable producer. */
    private fun inlinableDef(reg: RegisterOperand): Instruction? {
        if (isThis(reg)) return null
        val def = reg.ssaValue?.assign?.parent ?: return null
        return if (def.opcode in INLINABLE_ENUM_ARG_OPCODES) def else null
    }

    /** Whether [op] resolves to a self-contained (no free register) expression tree. */
    private fun canInline(op: Operand, visited: MutableSet<Instruction>): Boolean = when (op) {
        is LiteralOperand -> true
        is InstructionOperand -> canInlineInsn(op.instruction, visited)
        is RegisterOperand -> {
            if (isThis(op)) {
                false
            } else {
                val def = op.ssaValue?.assign?.parent
                def != null && def.opcode in INLINABLE_ENUM_ARG_OPCODES && canInlineInsn(def, visited)
            }
        }
    }

    private fun canInlineInsn(insn: Instruction, visited: MutableSet<Instruction>): Boolean {
        if (!visited.add(insn)) return false // cyclic ⇒ not inlinable
        if (insn.opcode !in INLINABLE_ENUM_ARG_OPCODES) return false
        for (i in 0 until insn.argCount) {
            if (!canInline(insn.getArg(i), visited)) return false
        }
        return true
    }

    // ---------- variables ----------

    private fun varKey(reg: RegisterOperand): Any = reg.ssaValue?.localVar ?: reg.ssaValue ?: reg.regNum

    private fun varRefFor(reg: RegisterOperand): VarRef {
        val key = varKey(reg)
        varRefs[key]?.let { return it }
        val preDeclared = isPreDeclared(reg)
        val explicit = reg.ssaValue?.localVar?.name?.let { JavaIdentifiers.sanitize(it) }
        val name = when {
            // Parameters/`this` already own a reserved, unique name — use it verbatim so a body
            // reference to a parameter matches its signature name exactly (both are sanitized).
            explicit != null && preDeclared -> explicit
            explicit != null -> names.unique(explicit)
            else -> names.forType(effectiveType(reg))
        }
        val ref = VarRef(nextVarId++, name)
        varRefs[key] = ref
        if (preDeclared) declared.add(key)
        return ref
    }

    /** Parameters and `this` already have a binding site, so they are never re-declared in the body. */
    private fun isPreDeclared(reg: RegisterOperand): Boolean {
        if (isThis(reg)) return true
        val lv = reg.ssaValue?.localVar
        if (lv != null && (lv.isThis || lv.contains(AttrFlag.METHOD_ARGUMENT))) return true
        val sv = reg.ssaValue
        return sv != null && sv.contains(AttrFlag.METHOD_ARGUMENT)
    }

    private fun isThis(reg: RegisterOperand): Boolean {
        val sv = reg.ssaValue ?: return false
        if (sv === method.thisArg) return true
        return sv.localVar?.isThis == true
    }

    private fun effectiveType(reg: RegisterOperand): IrType {
        val lvT = reg.ssaValue?.localVar?.type
        if (lvT != null && lvT.isTypeKnown) return lvT
        val svT = reg.ssaValue?.type
        if (svT != null && svT.isTypeKnown) return svT
        return lvT ?: svT ?: reg.type
    }

    private fun className(type: IrType): String = classNameForRef(type) ?: type.toString()

    private fun classNameForRef(type: IrType): String? = when (type) {
        is IrType.Object -> type.className
        is IrType.ArrayType -> classNameForRef(type.element)
        else -> null
    }

    // ---------- <clinit> terminal-return detection ----------

    /**
     * The terminal `return-void` of a `<clinit>` — the one that would render as the last statement of
     * the `static { … }` block — or null (not a `<clinit>`, or the body does not end in a plain
     * `return-void`). Deliberately finds ONLY the terminal one: it is the last instruction of the body's
     * last leaf block, so an early conditional return-void nested in a branch is never matched and stays
     * rendered (no silent code loss).
     */
    private fun findClinitTerminalReturn(): Instruction? {
        if (method.name != CLASS_INIT_NAME) return null
        val block = lastBodyBlock() ?: return null
        val last = block.instructions.lastOrNull { !it.contains(AttrFlag.DONT_GENERATE) } ?: return null
        return if (last.opcode == IrOpcode.RETURN && last.argCount == 0) last else null
    }

    /** The block whose last statement is the body's terminal statement, or null if it isn't a plain block. */
    private fun lastBodyBlock(): BasicBlock? = when (val region = method.region) {
        null -> method.blocks.lastOrNull { it.instructions.isNotEmpty() }
        else -> lastLeafBlock(region)
    }

    /**
     * The block reached by always taking the LAST child of [container]. Stops (null) at any control-flow
     * region (if/loop/switch/try/sync), which has no single trailing statement — so a `<clinit>` ending
     * in a branch yields no terminal return and its inner returns are left to render honestly.
     */
    private fun lastLeafBlock(container: IrContainer): BasicBlock? = when (container) {
        is BasicBlock -> container
        is SequenceRegion -> container.children.lastOrNull()?.let { lastLeafBlock(it) }
        else -> null
    }

    /** The boxed wrapper type whose `.TYPE` field names a primitive's `Class` (jadx's spelling). */
    private fun boxedType(kind: TypeKind): IrType = IrType.objectType(
        when (kind) {
            TypeKind.BOOLEAN -> "java.lang.Boolean"
            TypeKind.CHAR -> "java.lang.Character"
            TypeKind.BYTE -> "java.lang.Byte"
            TypeKind.SHORT -> "java.lang.Short"
            TypeKind.INT -> "java.lang.Integer"
            TypeKind.FLOAT -> "java.lang.Float"
            TypeKind.LONG -> "java.lang.Long"
            TypeKind.DOUBLE -> "java.lang.Double"
            TypeKind.VOID -> "java.lang.Void"
            TypeKind.OBJECT, TypeKind.ARRAY -> IrType.OBJECT_CLASS
        },
    )

    private companion object {
        /** The JVM static initializer name; its body renders as a `static { … }` block. */
        const val CLASS_INIT_NAME = "<clinit>"

        /** `java.lang.invoke` support types synthesized for an invoke-custom call site. */
        val METHOD_HANDLES_TYPE = IrType.objectType("java.lang.invoke.MethodHandles")
        val METHOD_TYPE_TYPE = IrType.objectType("java.lang.invoke.MethodType")

        /** The only supertypes an array is assignable to; a named class outside this set can't hold one. */
        val ARRAY_SUPERTYPE_CLASSES = setOf(
            IrType.OBJECT_CLASS, "java.io.Serializable", "java.lang.Cloneable",
        )
    }
}
