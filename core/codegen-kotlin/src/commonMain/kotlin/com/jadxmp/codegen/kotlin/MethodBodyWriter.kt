package com.jadxmp.codegen.kotlin

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
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeCustomInstruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
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
 * Cap on inlined-expression nesting depth (CLAUDE rule-4 fault isolation). `ExpressionShaping` folds each
 * single-use def into its use as a nested operand to a FIXPOINT, so a length-N single-use chain — a big
 * arithmetic / string-concat / nested-ternary run, routine in generated & obfuscated code — collapses into
 * ONE N-deep expression, which [MethodBodyWriter.emitInsnExpr] renders with N-deep recursion → a
 * `StackOverflowError`. An SOE is an [Error], not an [Exception], so the historical per-member
 * `catch(Exception)` markers missed it, and on wasmJs the stack is far shallower than the JVM's. We bound
 * the depth FAR above any faithful nesting (real code is a few dozen deep at most) yet FAR below overflow,
 * and THROW past it so the per-member backstop rolls the whole method back to one honest marker — a
 * proactive defense that never relies on catching a real SOE. Verified transparent to accurate output.
 */
private const val MAX_EXPR_DEPTH = 300

/** Thrown by [MethodBodyWriter.emitInsnExpr] past [MAX_EXPR_DEPTH]; caught by the per-member backstop (rule 4). */
private class ExpressionTooDeepException :
    RuntimeException("expression nesting exceeded $MAX_EXPR_DEPTH (single-use inline chain or cycle)")

/**
 * Emits one Kotlin method body: statements, expression trees (with Kotlin precedence), conditions, and
 * the structured region tree. **jadx: MethodGen + RegionGen + InsnGen + ConditionGen (Kotlin)**
 *
 * Mirrors the Java backend's region walk and per-method variable state (a [NameGenerator] and a
 * variable → [VarRef] map so a variable keeps one name and one metadata id), but every leaf is Kotlin:
 * `when` for a switch, `x as T` / `x is T`, `T(args)` construction, `.inv()` / named infix bitwise
 * ops, `.toInt()`-style conversions, no `new`, and no semicolons.
 *
 * First-pass control-flow notes:
 *  - a `FOR` loop is lowered to `init; while (cond) { body; update }` (Kotlin has no C-style `for`);
 *    this preserves every statement — `continue` running-the-update is a known first-pass divergence;
 *  - a multi-`catch` clause is emitted as one `catch` per type (Kotlin has no `A | B` multi-catch),
 *    duplicating the handler body rather than dropping any caught type.
 */
internal class MethodBodyWriter(
    private val code: CodeWriter,
    imports: ImportCollector,
    private val method: IrMethod,
    private val names: NameGenerator,
    paramNames: List<String>,
    // Instructions the caller has already accounted for elsewhere and that must NOT render as body
    // statements: an enum `<clinit>`'s enum-construction (its stores become entry declarations / are
    // synthesized by the compiler), or a `static final` field's single store that was hoisted into the
    // field's `val X = <expr>` initializer. See [KotlinCodeGenerator]. Empty for an ordinary body.
    private val suppressed: Set<Instruction> = emptySet(),
) {
    private val types = KotlinTypeRenderer(imports)

    // When set, a RegisterOperand renders as its (inlined) defining expression rather than a variable
    // name — used to render a hoisted `static final` initializer outside the `<clinit>` variable scope,
    // where the source registers have no declaration. Only enabled during [emitStaticFinalInit].
    private var inlineRegisters = false

    // The <clinit>'s TERMINAL return-void, if any: the fall-off `return` that is illegal inside a Kotlin
    // companion `init { … }` block (and enum residual init). Only this exact instruction is suppressed —
    // an early/conditional return-void is left to render honestly (no silent code loss).
    private val clinitTerminalReturn: Instruction? = findClinitTerminalReturn()

    // Identity/value keyed: LocalVar or SsaValue (identity) or a boxed regNum (value).
    private val varRefs = HashMap<Any, VarRef>()
    private val declared = HashSet<Any>()
    private var nextVarId = 0

    // A method PARAMETER that is reassigned in the body, keyed like [varKey]. In Kotlin a parameter is
    // `val` (immutable), so a body write `p = …` would not compile. We keep the parameter itself `val`
    // (unchanged in the signature) and, at method entry, introduce a mutable copy `var pCopy = p`; every
    // body occurrence of the parameter — read AND write — is redirected to the copy (see [setupParamCopies]
    // / [varRefFor]). Faithful: the copy is initialised to the parameter before any statement, so a read of
    // the copy equals the parameter on every path up to the first reassignment, and tracks it exactly after.
    private val paramCopyRefs = HashMap<Any, VarRef>()

    // Per-enclosing-loop update clause to replay before a `continue` targets that loop. A lowered
    // `for` pushes its update; every other loop pushes null (their `continue` is native). The innermost
    // loop (top of stack) is the one an unlabeled `continue` targets — see [emitForLoop] / M1.
    private val loopUpdateStack = ArrayDeque<Instruction?>()

    // A constructor delegation instruction already consumed into the Kotlin secondary-constructor header
    // (`: this(args)` / omitted implicit super) by [emitConstructorDelegationHeader]. It must NOT be
    // re-emitted as a body statement. Identity-compared; null when nothing was hoisted.
    private var headerDelegation: Instruction? = null

    // Current expression-tree nesting depth (rule-4 F2 guard — see [MAX_EXPR_DEPTH]). A fresh
    // MethodBodyWriter renders each member, so this counter never leaks across methods.
    private var exprDepth = 0

    init {
        for (p in paramNames) names.reserve(p)
    }

    // ---------- constructor delegation header ----------

    /**
     * Render a Kotlin secondary-constructor delegation into the header (`… : this(args)`) when this method
     * is a `<init>` whose body begins with a clean, faithfully-hoistable delegation, marking that
     * instruction to be skipped by [writeBody]. Called by the class emitter AFTER the parameter list and
     * BEFORE the opening brace, using the same writer instance so header/body variable naming stays in sync.
     *
     * ### Rule 4 — a constructor is high-risk, so we only touch a provably-safe shape and BAIL otherwise
     * (leaving the body's honest `// JADXMP ERROR` marker, never an invalid header or a dropped call):
     *  - **Only when the class extends `Object`/`Any`.** A real superclass is rendered on the class header
     *    WITH constructor parens (`: Base()`), which implies a primary constructor; a competing
     *    secondary-header `: super(...)` would conflict. Those (and enums, whose super is `java.lang.Enum`)
     *    are left to the marker.
     *  - **Only a genuine FIRST emittable statement.** If any statement precedes the delegation (Kotlin
     *    forbids code before a delegation) or it is nested/conditional, we bail.
     *  - A no-arg `super()` (the implicit `Any` super) is OMITTED — Kotlin supplies it. A `this(args)`
     *    delegation moves to `: this(args)`; its args are the constructor's params/constants (no local can
     *    precede a first-statement delegation), so they render identically to the body.
     */
    fun emitConstructorDelegationHeader() {
        if (method.name != "<init>") return
        // Gate on an Object/Any superclass (see kdoc): a real base or enum super stays with the marker.
        val superType = method.declaringClass.superType
        if (superType != null && superType != IrType.OBJECT) return

        val first = firstEmittableTopLevel() ?: return
        if (!isConstructorDelegation(first)) return
        val invoke = first as InvokeInstruction
        val firstArg = if (invoke.hasInstance) 1 else 0
        val delegationArgs = invoke.argCount - firstArg

        val targetName = (invoke.methodRef.declaringType as? IrType.Object)?.className
        val isThisCall = targetName == method.declaringClass.fullName

        when {
            // `this(args)` → header `: this(args)`.
            isThisCall -> {
                code.add(" : this")
                emitArgList(invoke, firstArg)
                headerDelegation = first
            }
            // A no-arg super to Object/Any is implicit in Kotlin → omit the delegation entirely.
            delegationArgs == 0 -> headerDelegation = first
            // A super WITH args cannot reach here for an Object super (Object's ctor is no-arg); if it
            // somehow does, don't guess — leave the honest body marker.
            else -> return
        }
    }

    /**
     * The first instruction [writeBody] would emit as a top-level statement, or null when it is nested in a
     * region (conditional/loop) or there is none. Only a straight top-level sequence of blocks is
     * considered — a nested region as the first child means the leading statement is conditional, so we
     * return null (⇒ no clean leading delegation).
     */
    private fun firstEmittableTopLevel(): Instruction? {
        val region = method.region
        val containers: List<IrContainer> = when {
            region is SequenceRegion -> region.children
            region == null -> method.blocks
            else -> return null // an If/Loop/etc. directly at the top: the first statement is conditional
        }
        for (c in containers) {
            when (c) {
                is BasicBlock -> for (insn in c.instructions) {
                    if (!isSkippedStatement(insn)) return insn
                }
                else -> return null // a nested region before any statement ⇒ leading stmt is conditional
            }
        }
        return null
    }

    /** Whether [emitStatement] would emit nothing for [insn] (so it does not count as a leading statement). */
    private fun isSkippedStatement(insn: Instruction): Boolean {
        if (insn.contains(AttrFlag.DONT_GENERATE)) return true
        return when (insn.opcode) {
            IrOpcode.NOP, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
            IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT, IrOpcode.GOTO,
            -> true
            else -> false
        }
    }

    // ---------- body entry ----------

    fun writeBody() {
        // Introduce `var pCopy = p` for every parameter reassigned in the body BEFORE emitting any
        // statement, so the copy is in scope (and initialised to the parameter) for every use.
        setupParamCopies()
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

    // ---------- reassigned-parameter copies (Kotlin params are `val`) ----------

    /**
     * Detect every parameter that is *reassigned* somewhere in the body and, for each, emit a mutable copy
     * `var <copy>: <T> = <param>` at method entry, registering the copy so [varRefFor] redirects all later
     * parameter reads/writes to it. A parameter that is only READ gets no copy (rule 4: only copy a genuine
     * reassignment). `this` is never copied (it cannot be reassigned).
     *
     * Faithfulness: the copy is declared before the first statement and initialised to the parameter, so it
     * dominates every use and equals the parameter until the first write — reading the copy never changes
     * which value is observed on any path, and no assignment is dropped. The parameter itself stays `val`
     * and unmodified in the signature. A reassigned register that is NOT a real parameter (e.g. a raw
     * register later reused as an ordinary local) is a distinct source variable with its own name and is
     * left untouched — only registers carrying the parameter's [AttrFlag.METHOD_ARGUMENT] marker are copied.
     */
    private fun setupParamCopies() {
        val writes = LinkedHashMap<Any, RegisterOperand>()
        forEachBodyStatement { insn ->
            val res = insn.result ?: return@forEachBodyStatement
            if (isReassignableParam(res)) {
                val key = varKey(res)
                if (key !in writes) writes[key] = res
            }
        }
        for ((key, reg) in writes) {
            val original = baseVarRefFor(reg) // the parameter's own ref/name (copy map not yet consulted)
            val copyRef = VarRef(nextVarId++, names.unique(original.name))
            // The type is INFERRED from the parameter (`var copy = p`), never spelled out: the parameter's
            // own static type is exactly right for the copy, and it avoids a mismatch between the signature
            // type (declared argType) and this backend's inferred local type (which may be more specific,
            // e.g. `ArrayList` vs a `List` parameter — an explicit copy type would then not compile).
            code.add("var ")
            code.variable(copyRef, declaration = true)
            code.add(" = ")
            code.variable(original, declaration = false)
            code.newLine()
            paramCopyRefs[key] = copyRef
            declared.add(key) // so a body write renders `copy = …`, never a fresh declaration
        }
    }

    /** A reassignable parameter: a genuine method argument (not `this`) — the one case a Kotlin `val` blocks. */
    private fun isReassignableParam(reg: RegisterOperand): Boolean = !isThis(reg) && isPreDeclared(reg)

    /**
     * Invoke [action] for every instruction [writeBody] would emit as a statement, walking the region tree
     * when present (else the linear block list) so detection sees exactly what emission does. Skips
     * instructions that emit nothing (DONT_GENERATE / structural opcodes) and the constructor delegation
     * already hoisted into the header — so a non-emitted write never triggers a spurious copy.
     */
    private fun forEachBodyStatement(action: (Instruction) -> Unit) {
        val region = method.region
        if (region != null) {
            walkRegionStatements(region, action)
        } else {
            for (block in method.blocks) for (insn in block.instructions) visitStatement(insn, action)
        }
    }

    private fun walkRegionStatements(region: Region, action: (Instruction) -> Unit) {
        when (region) {
            is SequenceRegion -> region.children.forEach { walkContainerStatements(it, action) }
            is IfRegion -> {
                walkRegionStatements(region.thenRegion, action)
                region.elseRegion?.let { walkRegionStatements(it, action) }
            }
            is LoopRegion -> {
                region[CodegenKeys.LOOP_INIT]?.let { visitStatement(it, action) }
                walkRegionStatements(region.body, action)
                region[CodegenKeys.LOOP_UPDATE]?.let { visitStatement(it, action) }
            }
            is SwitchRegion -> {
                region.cases.forEach { walkRegionStatements(it.body, action) }
                region.defaultCase?.let { walkRegionStatements(it, action) }
            }
            is TryCatchRegion -> {
                walkRegionStatements(region.tryRegion, action)
                region.catches.forEach { walkRegionStatements(it.body, action) }
                region.finallyRegion?.let { walkRegionStatements(it, action) }
            }
            is SyncRegion -> walkRegionStatements(region.body, action)
        }
    }

    private fun walkContainerStatements(c: IrContainer, action: (Instruction) -> Unit) {
        when (c) {
            is BasicBlock -> for (insn in c.instructions) visitStatement(insn, action)
            is Region -> walkRegionStatements(c, action)
            else -> {}
        }
    }

    private fun visitStatement(insn: Instruction, action: (Instruction) -> Unit) {
        if (insn === headerDelegation) return
        if (insn.contains(AttrFlag.DONT_GENERATE)) return
        if (isSkippedStatement(insn)) return
        action(insn)
    }

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
        // Pre-structuring stopgap: mark each block and emit its statements. Control flow is not
        // reconstructed yet (this path exists before structuring runs); the markers keep it honest.
        // The `// block N` labels are honesty markers ⇒ flag the method so error accounting sees them.
        // (RenderabilityGuard already flags such non-linear methods upstream; this keeps the coupling
        // self-contained in the backend and idempotent.)
        flagError(method, "unstructured control flow (pre-structuring block fallback)")
        for (b in blocks) {
            code.add("// block ").add(b.id.toString()).newLine()
            emitStatements(b)
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
            else -> {
                // An unexpected container kind ⇒ broken output; flag the method so it isn't read as clean.
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
        emitCondition(region.condition, KotlinPrec.LOWEST)
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

    private fun asSingleIf(region: Region): IfRegion? = when (region) {
        is IfRegion -> region
        is SequenceRegion -> (region.children.singleOrNull() as? IfRegion)
        else -> null
    }

    private fun emitLoop(region: LoopRegion) {
        when (region.kind) {
            LoopKind.DO_WHILE -> {
                code.add("do ")
                // A do-while `continue` jumps to the condition test natively — no update to replay.
                loopUpdateStack.addLast(null)
                openBrace()
                emitRegion(region.body)
                closeBrace()
                loopUpdateStack.removeLast()
                code.add(" while (")
                val cond = region.condition
                if (cond == null) code.add("true") else emitCondition(cond, KotlinPrec.LOWEST)
                code.add(")").newLine() // Kotlin do-while has no trailing semicolon
            }
            LoopKind.FOR -> emitForLoop(region)
            else -> {
                // WHILE, INFINITE, and (lacking iterable data) FOR_EACH all render as a while header.
                code.add("while (")
                val cond = region.condition
                if (cond == null) code.add("true") else emitCondition(cond, KotlinPrec.LOWEST)
                code.add(") ")
                loopUpdateStack.addLast(null)
                openBrace()
                emitRegion(region.body)
                closeBrace()
                loopUpdateStack.removeLast()
                code.newLine()
            }
        }
    }

    /**
     * Kotlin has no C-style `for`, so a counting loop is lowered to `init; while (cond) { body; update }`.
     * To keep `for` semantics exact, the update is ALSO replayed immediately before every `continue` that
     * targets this loop (a raw `continue` would jump straight to the condition and skip the update — the
     * M1 miscompile). The update instruction is pushed on [loopUpdateStack] so nested loops resolve a
     * `continue` to the correct (innermost) loop's update.
     */
    private fun emitForLoop(region: LoopRegion) {
        region[CodegenKeys.LOOP_INIT]?.let { emitStatement(it) }
        code.add("while (")
        region.condition?.let { emitCondition(it, KotlinPrec.LOWEST) } ?: code.add("true")
        code.add(") ")
        loopUpdateStack.addLast(region[CodegenKeys.LOOP_UPDATE])
        openBrace()
        emitRegion(region.body)
        // The back-edge (fall-through) update.
        //
        // PIPELINE CONTRACT: LOOP_INIT/LOOP_UPDATE are test-only today; when a structuring pass starts
        // populating them it MUST first extract the update instruction OUT of `region.body` (exactly as
        // the Java backend's native `for` header already requires). Otherwise the update lives both here
        // AND inside the body and is emitted twice — a double-count. This mirrors the Java backend's
        // for-header contract; keep them consistent.
        region[CodegenKeys.LOOP_UPDATE]?.let { emitStatement(it) }
        closeBrace()
        loopUpdateStack.removeLast()
        code.newLine()
    }

    private fun emitSwitch(region: SwitchRegion) {
        code.add("when (")
        emitOperand(region.selector, KotlinPrec.LOWEST)
        code.add(") ")
        openBrace()
        for (case in region.cases) {
            for ((i, key) in case.keys.withIndex()) {
                if (i > 0) code.add(", ")
                code.add(key.toString())
            }
            code.add(" -> ")
            openBrace()
            emitRegion(case.body)
            closeBrace()
            code.newLine()
        }
        region.defaultCase?.let { def ->
            code.add("else -> ")
            openBrace()
            emitRegion(def)
            closeBrace()
            code.newLine()
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
        // Kotlin has no multi-catch; emit one `catch` per type, each handling the (duplicated) body,
        // so no caught type is dropped.
        val exceptionTypes = if (catch.exceptionTypes.isEmpty()) listOf(IrType.THROWABLE) else catch.exceptionTypes
        for (type in exceptionTypes) {
            code.add(" catch (")
            val exVar = catch.exceptionVar as? RegisterOperand
            if (exVar != null) {
                val ref = varRefFor(exVar).also { declared.add(varKey(exVar)) }
                code.variable(ref, declaration = true)
            } else {
                code.add(names.unique("e"))
            }
            code.add(": ")
            emitClassName(type)
            code.add(") ")
            openBrace()
            emitRegion(catch.body)
            closeBrace()
        }
    }

    private fun emitSync(region: SyncRegion) {
        // Kotlin's `synchronized(lock) { }` is a stdlib function, not a statement keyword.
        code.add("synchronized(")
        emitOperand(region.monitor, KotlinPrec.LOWEST)
        code.add(") ")
        openBrace()
        emitRegion(region.body)
        closeBrace()
        code.newLine()
    }

    // ---------- statements ----------

    private fun emitStatement(insn: Instruction) {
        if (insn === headerDelegation) return // already rendered in the constructor header
        if (insn in suppressed) return // accounted for elsewhere (enum construction / hoisted field init)
        if (insn === clinitTerminalReturn) return // fall-off `return` is illegal inside `init { … }`
        if (insn.contains(AttrFlag.DONT_GENERATE)) return
        when (insn.opcode) {
            IrOpcode.NOP, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
            IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT,
            IrOpcode.GOTO,
            -> return
            else -> {
                if (!emitStatementCore(insn)) return
                code.newLine() // Kotlin: no trailing semicolon
            }
        }
    }

    /** Emit the statement text for [insn] without the trailing newline. Returns false if nothing emitted. */
    private fun emitStatementCore(insn: Instruction): Boolean {
        when (insn.opcode) {
            IrOpcode.RETURN -> {
                code.add("return")
                if (insn.argCount > 0) {
                    code.add(" ")
                    val arg = insn.getArg(0)
                    // A register that provably holds a `const 0` (null) but whose out-of-SSA-coalesced
                    // local carries a type definitely incompatible with the return type: emit `null`
                    // rather than the wrongly-typed variable name (see the Java backend for the rationale).
                    if (isReferenceType(method.returnType) && isNullConstRegister(arg) &&
                        definitelyIncompatibleReference(operandType(arg), method.returnType)
                    ) {
                        code.add("null")
                    } else {
                        emitOperand(arg, KotlinPrec.LOWEST)
                    }
                }
            }
            IrOpcode.THROW -> {
                code.add("throw ")
                emitOperand(insn.getArg(0), KotlinPrec.LOWEST)
            }
            IrOpcode.BREAK -> code.add("break")
            IrOpcode.CONTINUE -> {
                // Replay the enclosing lowered-`for` update before jumping to the condition (M1). For a
                // native loop (top of stack is null) this is a plain `continue`.
                //
                // INVARIANT (labeled continue): IrOpcode.CONTINUE carries no label/target, so it always
                // targets the innermost loop — hence `lastOrNull()` is exactly the right update to replay.
                // If a label/target is EVER added to CONTINUE, this innermost-only replay becomes wrong
                // (it would run the innermost loop's update while jumping to an outer loop). Whoever adds
                // labels must resolve the update by target here instead — fail loudly rather than silently.
                loopUpdateStack.lastOrNull()?.let { update ->
                    if (emitStatementCore(update)) code.newLine()
                }
                code.add("continue")
            }
            IrOpcode.INSTANCE_PUT -> emitInstancePut(insn)
            IrOpcode.STATIC_PUT -> emitStaticPut(insn)
            IrOpcode.ARRAY_PUT -> {
                // Canonical IR order (jadx: aput): args = [value, array, index].
                val value = insn.getArg(0)
                val array = insn.getArg(1)
                val index = insn.getArg(2)
                emitOperand(array, KotlinPrec.POSTFIX)
                code.add("[")
                emitOperand(index, KotlinPrec.LOWEST)
                code.add("] = ")
                emitOperand(value, KotlinPrec.LOWEST)
            }
            else -> {
                val result = insn.result
                if (result != null) {
                    emitAssignment(result, insn)
                } else {
                    // Kotlin constructor delegation is header-only (`: super(args)`); emitting `super(args)`
                    // as a body statement won't compile. Until a pass reconstructs the header, flag it so
                    // it is never *silently* invalid (S2) — the call text is still shown, not dropped.
                    if (isConstructorDelegation(insn)) {
                        // Broken output (a body-position `super(...)`) ⇒ flag the method HAS_ERROR AND emit the
                        // marker together, so error accounting (countErrors/reportedErrors) can't undercount it.
                        code.emitErrorMarker(method, "constructor delegation not reconstructed (Kotlin header-only)")
                    }
                    emitInsnExpr(insn, KotlinPrec.LOWEST)
                }
            }
        }
        return true
    }

    /** A `this(...)`/`super(...)` constructor delegation: an un-normalized `<init>` invoke on `this`. */
    private fun isConstructorDelegation(insn: Instruction): Boolean {
        val invoke = insn as? InvokeInstruction ?: return false
        if (invoke.opcode != IrOpcode.INVOKE || !invoke.methodRef.isConstructor) return false
        val receiver = invoke.instanceArg as? RegisterOperand ?: return false
        return isThis(receiver)
    }

    private fun emitAssignment(result: RegisterOperand, insn: Instruction) {
        val ref = varRefFor(result)
        val key = varKey(result)
        // A block-local temp (marked BLOCK_LOCAL_TEMP) emitted a SECOND time means its block was
        // DUPLICATED into another region position; the first copy's declaration is out of scope in a
        // sibling arm, so re-declare it locally in each copy. Fires ONLY on re-emission — a no-op for a
        // block emitted once. Cross-block / coalesced values are never marked (single declaration kept).
        // A reassigned-parameter copy is a single method-entry declaration; a body write is always a plain
        // reassignment to it (never a re-declaration), even inside a duplicated block.
        val isParamCopy = key in paramCopyRefs
        val redeclareDuplicate = !isParamCopy && key in declared && insn.contains(AttrFlag.BLOCK_LOCAL_TEMP)
        if (redeclareDuplicate || (key !in declared && !isPreDeclared(result))) {
            // First-pass locals are `var` (always reassignable → always compiles); the declared type is
            // spelled out since inference from the RHS alone can be unsound (e.g. a null literal).
            code.add("var ")
            code.variable(ref, declaration = true)
            val declType = effectiveType(result)
            // A local whose initializer is the `null` literal must be declared nullable, or kotlinc
            // rejects `var x: T = null` ("null cannot be a value of a non-null type"). Only the literal-
            // null case widens to `T?`; a non-null initializer keeps the precise non-null type.
            val nullable = isReferenceType(declType) && isNullLiteralExpr(insn)
            code.add(": ").add(types.render(declType)).add(if (nullable) "?" else "")
            declared.add(key)
        } else {
            code.variable(ref, declaration = false)
        }
        code.add(" = ")
        emitInsnExpr(insn, KotlinPrec.LOWEST)
    }

    private fun emitInstancePut(insn: Instruction) {
        val field = (insn as? FieldInstruction)?.fieldRef
        emitOperand(insn.getArg(0), KotlinPrec.POSTFIX)
        code.add(".")
        emitFieldName(field)
        code.add(" = ")
        emitOperand(insn.getArg(insn.argCount - 1), KotlinPrec.LOWEST)
    }

    private fun emitStaticPut(insn: Instruction) {
        val field = (insn as? FieldInstruction)?.fieldRef
        if (field != null) {
            emitClassName(field.declaringType)
            code.add(".")
        }
        emitFieldName(field)
        code.add(" = ")
        emitOperand(insn.getArg(insn.argCount - 1), KotlinPrec.LOWEST)
    }

    private fun emitFieldName(field: FieldRef?) {
        if (field != null) {
            code.attachReference(FieldNodeRef(className(field.declaringType), field.name))
            code.add(KotlinIdentifiers.sanitize(field.name))
        } else {
            // A field-opcode insn that is not a FieldInstruction carries no field name. The decoder always
            // builds the right subclass, so this is unreachable today — but fabricating a `field` identifier
            // would be a silent miscompile if the invariant ever broke. BAIL honestly instead (rule 4).
            code.emitErrorMarker(method, "field opcode without a FieldInstruction (no field name)")
        }
    }

    // ---------- expressions ----------

    private fun emitOperand(op: Operand, minPrec: Int) {
        when (op) {
            is RegisterOperand -> {
                // While rendering a hoisted `static final` initializer, a register renders as its
                // (inlined) self-contained defining expression, since the <clinit> locals it names have no
                // declaration at the property position.
                val def = if (inlineRegisters) inlinableDef(op) else null
                if (def != null) emitInsnExpr(def, minPrec) else emitRegister(op)
            }
            is LiteralOperand -> code.add(KotlinLiterals.format(op))
            is InstructionOperand -> emitInsnExpr(op.instruction, minPrec)
        }
    }

    // ---------- hoisted static-final field initializer ----------

    /**
     * Render the value stored by [store] (a `<clinit>` `STATIC_PUT`) as a `static final` field's `val X =
     * <expr>` initializer, returning true if it was rendered. The value must be a fully self-contained
     * expression tree — no free `<clinit>` register/`this` — because it is emitted at the property
     * position, outside the static block's variable scope; otherwise false is returned (rendering nothing)
     * and the caller keeps the store in the init block (CLAUDE rule 4: never a dangling reference).
     */
    fun emitStaticFinalInit(store: Instruction): Boolean {
        val value = store.getArg(store.argCount - 1)
        if (!canInline(value, HashSet())) return false
        inlineRegisters = true
        emitOperand(value, KotlinPrec.LOWEST)
        inlineRegisters = false
        return true
    }

    /**
     * Plan hoisting [store] (a `<clinit>` `STATIC_PUT`) into a `val X = <expr>` initializer: the set of
     * `<clinit>` instructions to suppress — the store PLUS the self-contained producer tree that feeds its
     * value (which is inlined into the initializer and would otherwise re-emit as dead residual). Returns
     * null when the value isn't self-contained, or when any producer's result is ALSO read by a statement
     * outside the tree (hoisting it would either dangle or double-execute a side effect) — the caller then
     * leaves the store in the init block (CLAUDE rule 4).
     */
    fun planStaticFinalInline(store: Instruction, alreadySuppressed: Set<Instruction>): Set<Instruction>? {
        if (store.argCount == 0) return null
        val tree = inlinedProducerTree(store.getArg(store.argCount - 1)) ?: return null
        val suppressedTree = HashSet(tree).apply { add(store) }
        val treeResults = tree.mapNotNull { it.result?.ssaValue }.toHashSet()
        if (treeResults.isNotEmpty()) {
            for (block in method.blocks) {
                for (insn in block.instructions) {
                    if (insn in suppressedTree) continue
                    if (readsAny(insn, treeResults)) return null // producer shared with a surviving statement
                }
            }
        }
        // Order hazard: hoisting evaluates the initializer at the PROPERTY position, BEFORE every surviving
        // `<clinit>` statement (they render in the init block, after all properties). If the tree reads
        // mutable heap state (a field/array element) or performs a side-effecting call, moving it earlier
        // could observe a not-yet-written value or reorder an effect (silent wrong value). So an
        // order-sensitive tree may be hoisted ONLY when no surviving statement remains to reorder against;
        // a purely literal/arithmetic tree is order-independent and always safe. (CLAUDE rule 4.)
        if (tree.any { it.opcode in ORDER_SENSITIVE_OPCODES }) {
            val excluded = HashSet(suppressedTree).apply { addAll(alreadySuppressed) }
            if (hasSurvivingStatement(excluded)) return null
        }
        return suppressedTree
    }

    /** Whether any `<clinit>` statement outside [excluded] would still render in the init block. */
    private fun hasSurvivingStatement(excluded: Set<Instruction>): Boolean {
        for (block in method.blocks) {
            for (insn in block.instructions) {
                if (insn in excluded) continue
                if (insn === clinitTerminalReturn) continue
                if (insn.contains(AttrFlag.DONT_GENERATE)) continue
                if (isScaffolding(insn)) continue
                return true
            }
        }
        return false
    }

    /** Control-flow scaffolding that never renders as a statement on its own. */
    private fun isScaffolding(insn: Instruction): Boolean = when (insn.opcode) {
        IrOpcode.NOP, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
        IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT, IrOpcode.GOTO,
        -> true
        IrOpcode.RETURN -> insn.argCount == 0
        else -> false
    }

    /** The producer instructions feeding [value] (inlined into the initializer), or null if not self-contained. */
    private fun inlinedProducerTree(value: Operand): Set<Instruction>? {
        if (!canInline(value, HashSet())) return null
        val out = HashSet<Instruction>()
        fun walk(op: Operand) {
            val insn = when (op) {
                is InstructionOperand -> op.instruction
                is RegisterOperand -> op.ssaValue?.assign?.parent
                else -> null
            } ?: return
            if (!out.add(insn)) return
            for (i in 0 until insn.argCount) walk(insn.getArg(i))
        }
        walk(value)
        return out
    }

    private fun readsAny(insn: Instruction, values: Set<com.jadxmp.ir.node.SsaValue>): Boolean {
        for (i in 0 until insn.argCount) {
            when (val op = insn.getArg(i)) {
                is RegisterOperand -> if (op.ssaValue in values) return true
                is InstructionOperand -> if (readsAny(op.instruction, values)) return true
                else -> {}
            }
        }
        return false
    }

    /** The defining instruction to inline for [reg], or null if it isn't a pure inlinable producer. */
    private fun inlinableDef(reg: RegisterOperand): Instruction? {
        if (isThis(reg)) return null
        val def = reg.ssaValue?.assign?.parent ?: return null
        return if (def.opcode in INLINABLE_OPCODES) def else null
    }

    /** Whether [op] resolves to a self-contained (no free register/`this`) expression tree. */
    private fun canInline(op: Operand, visited: MutableSet<Instruction>): Boolean = when (op) {
        is LiteralOperand -> true
        is InstructionOperand -> canInlineInsn(op.instruction, visited)
        is RegisterOperand -> {
            if (isThis(op)) {
                false
            } else {
                val def = op.ssaValue?.assign?.parent
                def != null && def.opcode in INLINABLE_OPCODES && canInlineInsn(def, visited)
            }
        }
    }

    private fun canInlineInsn(insn: Instruction, visited: MutableSet<Instruction>): Boolean {
        if (!visited.add(insn)) return false // cyclic ⇒ not inlinable
        if (insn.opcode !in INLINABLE_OPCODES) return false
        for (i in 0 until insn.argCount) {
            if (!canInline(insn.getArg(i), visited)) return false
        }
        return true
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
                if (value != null) code.add(KotlinLiterals.stringLiteral(value))
                else code.emitErrorMarker(method, "const-string without a ConstStringInstruction")
            }
            IrOpcode.CONST_CLASS -> {
                val t = referencedType(insn) ?: insn.result?.type ?: IrType.OBJECT
                wrapped(KotlinPrec.POSTFIX, minPrec) {
                    emitClassName(t) // `List::class.java`, never `List<*>::class.java`
                    code.add("::class.java")
                }
            }
            IrOpcode.ARITH, IrOpcode.NEG -> emitArith(insn, minPrec)
            IrOpcode.NOT -> wrapped(KotlinPrec.POSTFIX, minPrec) {
                emitOperand(insn.getArg(0), KotlinPrec.POSTFIX)
                code.add(".inv()")
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
            IrOpcode.CHECK_CAST -> wrapped(KotlinPrec.AS, minPrec) {
                val t = referencedType(insn) ?: insn.result?.type ?: IrType.OBJECT
                emitOperand(insn.getArg(0), KotlinPrec.AS)
                code.add(" as ")
                emitTypeRef(t)
            }
            IrOpcode.INSTANCE_OF -> wrapped(KotlinPrec.NAMED_CHECK, minPrec) {
                emitOperand(insn.getArg(0), KotlinPrec.NAMED_CHECK)
                code.add(" is ")
                emitTypeRef(referencedType(insn) ?: IrType.OBJECT)
            }
            IrOpcode.CMP -> emitCompare(insn)
            IrOpcode.IF -> emitIfExpr(insn as IfInstruction, minPrec)
            IrOpcode.TERNARY -> wrapped(KotlinPrec.LOWEST, minPrec) {
                // Kotlin has no ternary; `a ? b : c` is an `if` expression.
                code.add("if (")
                emitOperand(insn.getArg(0), KotlinPrec.LOWEST)
                code.add(") ")
                emitOperand(insn.getArg(1), KotlinPrec.LOWEST)
                code.add(" else ")
                emitOperand(insn.getArg(2), KotlinPrec.LOWEST)
            }
            IrOpcode.STRING_CONCAT -> wrapped(KotlinPrec.ADD, minPrec) {
                if (insn.argCount == 0) {
                    code.add("\"\"")
                } else {
                    for (i in 0 until insn.argCount) {
                        if (i > 0) code.add(" + ")
                        emitOperand(insn.getArg(i), if (i == 0) KotlinPrec.ADD else KotlinPrec.ADD + 1)
                    }
                }
            }
            IrOpcode.NEW_INSTANCE -> {
                // A bare NEW_INSTANCE reaching expression codegen is ALWAYS a bug, never a legit no-arg
                // allocation: a real no-arg `new` is a CONSTRUCTOR insn (→ `T()` via emitInvoke). This path
                // is only hit when ConstructorReconstruction could not fuse the `new-instance` with its
                // paired `<init>(args)` invoke (it chases only MOVE; bails on PHI/other defs), leaving an
                // orphan raw allocation. Rendering `T()` here would SILENTLY drop the constructor args (the
                // leftover `<init>` renders its own spurious `T(args)` separately) — precisely the rule-4
                // silent miscompile. It is not faithfully renderable in codegen alone, so BAIL honestly.
                code.emitErrorMarker(method, "unfused new-instance / constructor not reconstructed")
            }
            IrOpcode.NEW_ARRAY -> emitNewArray(insn, minPrec)
            IrOpcode.FILLED_NEW_ARRAY -> emitFilledNewArray(insn)
            IrOpcode.ARRAY_LENGTH -> {
                emitOperand(insn.getArg(0), KotlinPrec.POSTFIX)
                code.add(".size")
            }
            IrOpcode.ARRAY_GET -> {
                emitOperand(insn.getArg(0), KotlinPrec.POSTFIX)
                code.add("[")
                emitOperand(insn.getArg(1), KotlinPrec.LOWEST)
                code.add("]")
            }
            IrOpcode.INSTANCE_GET -> emitInstanceGet(insn)
            IrOpcode.STATIC_GET -> emitStaticGet(insn)
            IrOpcode.INVOKE, IrOpcode.CONSTRUCTOR -> emitInvoke(insn)
            else -> emitUnknownExpr(insn)
        }
        exprDepth--
    }

    private fun emitArith(insn: Instruction, minPrec: Int) {
        val op = (insn as? ArithInstruction)?.op
        if (op == null || op == ArithOp.NEGATE || insn.argCount < 2) {
            wrapped(KotlinPrec.PREFIX, minPrec) {
                code.add("-")
                val arg = insn.getArg(0)
                // Guard `- -x` (would lex as decrement `--`): parenthesize a following minus.
                if (operandStartsWithMinus(arg)) {
                    code.add("(")
                    emitOperand(arg, KotlinPrec.LOWEST)
                    code.add(")")
                } else {
                    emitOperand(arg, KotlinPrec.PREFIX)
                }
            }
            return
        }
        val prec = op.kotlinPrecedence()
        wrapped(prec, minPrec) {
            emitOperand(insn.getArg(0), prec)
            code.add(" ").add(op.kotlinSymbol()).add(" ")
            emitOperand(insn.getArg(1), prec + 1)
        }
    }

    private fun operandStartsWithMinus(op: Operand): Boolean = when (op) {
        is LiteralOperand -> KotlinLiterals.format(op).startsWith("-")
        is InstructionOperand -> instructionStartsWithMinus(op.instruction)
        is RegisterOperand -> false
    }

    private fun instructionStartsWithMinus(insn: Instruction): Boolean = when (insn.opcode) {
        IrOpcode.NEG -> true
        IrOpcode.ARITH -> (insn as? ArithInstruction)?.op == ArithOp.NEGATE
        IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG, IrOpcode.CONST ->
            insn.argCount > 0 && operandStartsWithMinus(insn.getArg(0))
        else -> false
    }

    private fun emitPrimitiveCast(insn: Instruction, minPrec: Int) {
        val target = insn.result?.type ?: IrType.INT
        // Kotlin uses conversion functions (`x.toLong()`), not a `(T)` cast, for numeric conversions.
        wrapped(KotlinPrec.POSTFIX, minPrec) {
            emitOperand(insn.getArg(0), KotlinPrec.POSTFIX)
            code.add(".").add(numericConversion(target))
        }
    }

    private fun numericConversion(target: IrType): String = when ((target as? IrType.Primitive)?.kind) {
        TypeKind.LONG -> "toLong()"
        TypeKind.FLOAT -> "toFloat()"
        TypeKind.DOUBLE -> "toDouble()"
        TypeKind.BYTE -> "toByte()"
        TypeKind.SHORT -> "toShort()"
        TypeKind.CHAR -> "toChar()"
        else -> "toInt()"
    }

    private fun emitCompare(insn: Instruction) {
        // CMP (-1/0/1) is rare standalone (structuring folds it into an IF). Kotlin's `compareTo` matches.
        emitOperand(insn.getArg(0), KotlinPrec.POSTFIX)
        code.add(".compareTo(")
        emitOperand(insn.getArg(1), KotlinPrec.LOWEST)
        code.add(")")
    }

    private fun emitIfExpr(insn: IfInstruction, minPrec: Int) {
        val prec = insn.condition.kotlinPrecedence()
        wrapped(prec, minPrec) {
            emitOperand(insn.getArg(0), prec)
            code.add(" ").add(insn.condition.symbol).add(" ")
            if (insn.argCount > 1) emitOperand(insn.getArg(1), prec + 1) else code.add("0")
        }
    }

    private fun emitNewArray(insn: Instruction, minPrec: Int) {
        // referencedType is the WHOLE array type. `new int[n]` → `IntArray(n)`; a reference (or
        // multi-dimensional) array → `arrayOfNulls<Element>(n)`.
        val arrayType = referencedType(insn) ?: insn.result?.type
        val element = arrayType?.arrayElement
        val size: () -> Unit = {
            // A NEW_ARRAY always carries its size operand (decoder guarantee), so the else arm is unreachable
            // today. Fabricating `0` would silently change the allocation size, so BAIL honestly (rule 4).
            if (insn.argCount > 0) emitOperand(insn.getArg(0), KotlinPrec.LOWEST)
            else code.emitErrorMarker(method, "new-array without a size operand")
        }
        if (element is IrType.Primitive) {
            code.add(primitiveArrayConstructor(element.kind)).add("(")
            size()
            code.add(")")
        } else {
            // `arrayOfNulls<T>(n)` has type `Array<T?>`, but the value flows into non-null `Array<T>`
            // positions (the declared local/param/field type is non-null, matching `new T[n]`'s Java
            // type). Reconcile with a cast to the whole array type so the initializer type matches the
            // declaration — otherwise kotlinc reports `expected 'Array<T>', actual 'Array<T?>'`. The
            // (unchecked) cast is warning-only and semantically faithful: the runtime array is the same.
            wrapped(KotlinPrec.AS, minPrec) {
                code.add("arrayOfNulls<")
                emitTypeName(element ?: IrType.OBJECT)
                code.add(">(")
                size()
                code.add(") as ")
                if (arrayType is IrType.ArrayType) {
                    emitTypeName(arrayType)
                } else {
                    code.add("Array<")
                    emitTypeName(element ?: IrType.OBJECT)
                    code.add(">")
                }
            }
        }
    }

    private fun emitFilledNewArray(insn: Instruction) {
        val arrayType = referencedType(insn) ?: insn.result?.type
        val element = arrayType?.arrayElement
        val factory = if (element is IrType.Primitive) primitiveArrayOf(element.kind) else "arrayOf"
        code.add(factory).add("(")
        for (i in 0 until insn.argCount) {
            if (i > 0) code.add(", ")
            emitOperand(insn.getArg(i), KotlinPrec.LOWEST)
        }
        code.add(")")
    }

    private fun primitiveArrayConstructor(kind: TypeKind): String = when (kind) {
        TypeKind.BOOLEAN -> "BooleanArray"
        TypeKind.CHAR -> "CharArray"
        TypeKind.BYTE -> "ByteArray"
        TypeKind.SHORT -> "ShortArray"
        TypeKind.INT -> "IntArray"
        TypeKind.FLOAT -> "FloatArray"
        TypeKind.LONG -> "LongArray"
        TypeKind.DOUBLE -> "DoubleArray"
        else -> "arrayOfNulls<Any>"
    }

    private fun primitiveArrayOf(kind: TypeKind): String = when (kind) {
        TypeKind.BOOLEAN -> "booleanArrayOf"
        TypeKind.CHAR -> "charArrayOf"
        TypeKind.BYTE -> "byteArrayOf"
        TypeKind.SHORT -> "shortArrayOf"
        TypeKind.INT -> "intArrayOf"
        TypeKind.FLOAT -> "floatArrayOf"
        TypeKind.LONG -> "longArrayOf"
        TypeKind.DOUBLE -> "doubleArrayOf"
        else -> "arrayOf"
    }

    private fun referencedType(insn: Instruction): IrType? = (insn as? TypeInstruction)?.referencedType

    private fun emitInstanceGet(insn: Instruction) {
        val field = (insn as? FieldInstruction)?.fieldRef
        emitOperand(insn.getArg(0), KotlinPrec.POSTFIX)
        code.add(".")
        emitFieldName(field)
    }

    private fun emitStaticGet(insn: Instruction) {
        val field = (insn as? FieldInstruction)?.fieldRef
        if (field != null) {
            emitClassName(field.declaringType)
            code.add(".")
        }
        emitFieldName(field)
    }

    private fun emitInvoke(insn: Instruction) {
        if (insn is InvokeCustomInstruction) {
            emitInvokeCustom(insn, KotlinPrec.LOWEST)
            return
        }
        val invoke = insn as? InvokeInstruction
        if (invoke == null) {
            emitUnknownExpr(insn)
            return
        }
        val target = invoke.methodRef
        val kind = invoke.invokeKind

        // A normalized constructor renders `T(args)` (Kotlin has no `new`). The callee is a bare name.
        if (invoke.opcode == IrOpcode.CONSTRUCTOR) {
            emitClassName(target.declaringType)
            emitArgList(invoke, 0)
            return
        }
        // An un-normalized `<init>` invoke: constructor delegation (this()/super()) vs a `new` on
        // another object. The receiver tells them apart (same distinction as the Java backend).
        if (target.isConstructor) {
            val receiver = invoke.instanceArg
            val firstArg = if (invoke.hasInstance) 1 else 0
            if (receiver is RegisterOperand && isThis(receiver)) {
                val enclosingName = method.declaringClass.fullName
                val targetName = (target.declaringType as? IrType.Object)?.className
                // Kotlin constructor delegation is header-only; emitted inline here as a best-effort
                // (a later pass reconstructs primary/secondary-constructor delegation).
                code.add(if (targetName == enclosingName) "this" else "super")
            } else {
                emitClassName(target.declaringType)
            }
            emitArgList(invoke, firstArg)
            return
        }

        when {
            kind == InvokeKind.STATIC -> emitClassName(target.declaringType)
            kind == InvokeKind.SUPER -> code.add("super")
            else -> {
                val receiver = invoke.instanceArg
                if (receiver != null) emitOperand(receiver, KotlinPrec.POSTFIX) else code.add("this")
            }
        }
        code.add(".")
        code.attachReference(MethodNodeRef(className(target.declaringType), target.name, target.paramTypes.map { it.toString() }))
        code.add(KotlinIdentifiers.sanitize(target.name))
        emitArgList(invoke, if (kind == InvokeKind.STATIC) 0 else 1)
    }

    /**
     * Render a raw `invoke-custom` as a polymorphic-style call through the resolved bootstrap, adapted
     * to Kotlin syntax (postfix `as` cast, `X::class.java` class literals):
     * `bootstrap(MethodHandles.lookup(), "<name>", MethodType.methodType(<Ret>::class.java, <P>.TYPE, …))
     * .dynamicInvoker().invoke(<args>) as <Ret> /* invoke-custom */`.
     * A non-renderable shape (marked in decode) bails to an error marker (rule 4).
     */
    private fun emitInvokeCustom(insn: InvokeCustomInstruction, minPrec: Int) {
        if (!insn.renderable) {
            code.emitErrorMarker(method, "unsupported invoke-custom (field/non-static handle or extra bootstrap args)")
            return
        }
        val returnType = insn.protoReturnType
        val body = {
            emitBootstrapCall(insn)
            code.add(".dynamicInvoker().invoke")
            emitArgList(insn, 0)
            if (returnType != IrType.VOID) {
                code.add(" as ")
                emitTypeRef(returnType)
            }
            code.add(" /* invoke-custom */")
        }
        if (returnType != IrType.VOID) wrapped(KotlinPrec.AS, minPrec) { body() } else body()
    }

    /**
     * `bootstrap(MethodHandles.lookup(), "<name>", MethodType.methodType(<Ret>::class.java, <P>.TYPE, …))`.
     * A same-class static bootstrap omits the class qualifier.
     */
    private fun emitBootstrapCall(insn: InvokeCustomInstruction) {
        val boot = insn.bootstrapMethod
        if ((boot.declaringType as? IrType.Object)?.className != method.declaringClass.fullName) {
            emitClassName(boot.declaringType)
            code.add(".")
        }
        code.attachReference(MethodNodeRef(className(boot.declaringType), boot.name, boot.paramTypes.map { it.toString() }))
        code.add(KotlinIdentifiers.sanitize(boot.name))
        code.add("(")
        emitClassName(METHOD_HANDLES_TYPE)
        code.add(".lookup(), ")
        code.add(KotlinLiterals.stringLiteral(insn.callSiteName))
        code.add(", ")
        emitMethodType(insn.protoReturnType, insn.protoParamTypes)
        code.add(")")
    }

    /** `MethodType.methodType(<Ret token>, <param tokens…>)`. */
    private fun emitMethodType(returnType: IrType, paramTypes: List<IrType>) {
        emitClassName(METHOD_TYPE_TYPE)
        code.add(".methodType(")
        emitTypeToken(returnType)
        for (p in paramTypes) {
            code.add(", ")
            emitTypeToken(p)
        }
        code.add(")")
    }

    /** A `Class` token: a primitive as `<Boxed>.TYPE`, a reference as `<Type>::class.java`. */
    private fun emitTypeToken(type: IrType) {
        if (type is IrType.Primitive) {
            emitClassName(boxedType(type.kind))
            code.add(".TYPE")
        } else {
            emitClassName(type)
            code.add("::class.java")
        }
    }

    /** The boxed wrapper type whose `.TYPE` field names a primitive's `Class`. */
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

    private fun emitArgList(insn: Instruction, firstArgIndex: Int) {
        code.add("(")
        var emitted = 0
        for (i in firstArgIndex until insn.argCount) {
            if (emitted > 0) code.add(", ")
            emitOperand(insn.getArg(i), KotlinPrec.LOWEST)
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
            emitOperand(insn.getArg(0), KotlinPrec.POSTFIX)
        } else {
            code.add("null")
        }
        code.add(" /* ").add(insn.opcode.name).add(" */")
    }

    private fun emitTypeRef(type: IrType) {
        classNameForRef(type)?.let { code.attachReference(com.jadxmp.codegen.ClassNodeRef(it)) }
        code.add(types.render(type))
    }

    private fun emitTypeName(type: IrType) = emitTypeRef(type)

    /**
     * A **bare class name** for a position where only a name is syntactically legal — a static receiver
     * (`List.of`), a constructor callee (`ArrayList(..)`), `X::class`, or a catch type. Uses
     * [KotlinTypeRenderer.classNameOf], which never emits a `<*>` raw-generic projection (that would be
     * illegal here), so it stays byte-for-byte identical to the old behaviour for every non-raw type.
     */
    private fun emitClassName(type: IrType) {
        classNameForRef(type)?.let { code.attachReference(com.jadxmp.codegen.ClassNodeRef(it)) }
        code.add(types.classNameOf(type))
    }

    // ---------- conditions ----------

    private fun emitCondition(cond: Condition, minPrec: Int) {
        when (cond) {
            is Condition.Compare -> {
                val prec = cond.op.kotlinPrecedence()
                wrapped(prec, minPrec) {
                    emitOperand(cond.left, prec)
                    code.add(" ").add(cond.op.symbol).add(" ")
                    emitOperand(cond.right, prec + 1)
                }
            }
            is Condition.BoolTest -> emitOperand(cond.operand, minPrec)
            is Condition.Not -> emitNot(cond.negated, minPrec)
            is Condition.And -> emitJunction(cond.terms, "&&", KotlinPrec.CONJUNCTION, minPrec)
            is Condition.Or -> emitJunction(cond.terms, "||", KotlinPrec.DISJUNCTION, minPrec)
        }
    }

    private fun emitNot(inner: Condition, minPrec: Int) {
        when (inner) {
            is Condition.Compare -> emitCondition(Condition.Compare(inner.op.negate(), inner.left, inner.right), minPrec)
            is Condition.Not -> emitCondition(inner.negated, minPrec)
            is Condition.BoolTest -> wrapped(KotlinPrec.PREFIX, minPrec) {
                code.add("!")
                emitOperand(inner.operand, KotlinPrec.PREFIX)
            }
            else -> wrapped(KotlinPrec.PREFIX, minPrec) {
                code.add("!(")
                emitCondition(inner, KotlinPrec.LOWEST)
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

    // ---------- variables ----------

    private fun varKey(reg: RegisterOperand): Any = reg.ssaValue?.localVar ?: reg.ssaValue ?: reg.regNum

    private fun varRefFor(reg: RegisterOperand): VarRef {
        // A reassigned parameter renders as its mutable body copy everywhere in the body. The map is empty
        // until [setupParamCopies] runs (start of [writeBody]), so the constructor delegation header — which
        // is emitted BEFORE the body and is out of the copy's scope — still reads the original parameter.
        paramCopyRefs[varKey(reg)]?.let { return it }
        return baseVarRefFor(reg)
    }

    private fun baseVarRefFor(reg: RegisterOperand): VarRef {
        val key = varKey(reg)
        varRefs[key]?.let { return it }
        val preDeclared = isPreDeclared(reg)
        val explicit = reg.ssaValue?.localVar?.name?.let { KotlinIdentifiers.sanitize(it) }
        val name = when {
            explicit != null && preDeclared -> explicit
            explicit != null -> names.unique(explicit)
            // A type-derived fallback name is a valid *Java* identifier but can still be a Kotlin hard
            // keyword the shared allocator does not know about (a class `In`/`Is`/`When` de-capitalises to
            // `in`/`is`/`when`). Backtick-escape so a generated name is never emitted as bare-invalid Kotlin;
            // a normal name (`point`, `str`, `i`) is not a keyword and passes through unchanged.
            else -> KotlinIdentifiers.sanitize(names.forType(effectiveType(reg)))
        }
        val ref = VarRef(nextVarId++, name)
        varRefs[key] = ref
        if (preDeclared) declared.add(key)
        return ref
    }

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

    /** The coalesced local type a register renders with. */
    private fun operandType(op: Operand): IrType = if (op is RegisterOperand) effectiveType(op) else op.type

    /**
     * True if [op] is a register whose single SSA definition is a `const 0` — it provably holds the null
     * constant on this read. Per-SSA-version: a register reassigned before this use points at a different
     * SSA value, so this correctly returns false for it.
     */
    private fun isNullConstRegister(op: Operand): Boolean {
        if (op !is RegisterOperand || isThis(op)) return false
        val def = op.ssaValue?.assign?.parent ?: return false
        return def.opcode == IrOpcode.CONST && def.argCount > 0 &&
            (def.getArg(0) as? LiteralOperand)?.value == 0L
    }

    /**
     * True when [source] and [target] are reference types definitely not assignable in either direction —
     * restricted to what codegen can decide without a class graph: an array vs a named non-array class
     * (only `Any`/`Serializable`/`Cloneable` may hold an array), or two primitive-element arrays with
     * different element kinds. Conservative: returns false whenever a subtype relation is possible.
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

    /** A reference type (object/array/type-variable/wildcard, or an all-reference partial) — one that `?` applies to. */
    private fun isReferenceType(type: IrType): Boolean = when (type) {
        is IrType.Object, is IrType.ArrayType, is IrType.TypeVariable, is IrType.Wildcard -> true
        is IrType.Unknown -> type.possible.all { it == TypeKind.OBJECT || it == TypeKind.ARRAY }
        else -> false
    }

    /**
     * True when [insn] renders to exactly the `null` literal — a CONST (or a MOVE/one-arg forwarding one)
     * whose operand is the reference-typed zero constant. Reuses [KotlinLiterals.format] so the check
     * agrees with what is actually emitted rather than re-deriving the "is this null" rule.
     */
    private fun isNullLiteralExpr(insn: Instruction): Boolean = when (insn.opcode) {
        IrOpcode.CONST, IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG ->
            insn.argCount > 0 && isNullOperand(insn.getArg(0))
        else -> false
    }

    private fun isNullOperand(op: Operand): Boolean = when (op) {
        is LiteralOperand -> KotlinLiterals.format(op) == "null"
        is InstructionOperand -> isNullLiteralExpr(op.instruction)
        is RegisterOperand -> false
    }

    private fun className(type: IrType): String = classNameForRef(type) ?: type.toString()

    private fun classNameForRef(type: IrType): String? = when (type) {
        is IrType.Object -> type.className
        is IrType.ArrayType -> classNameForRef(type.element)
        else -> null
    }

    // ---------- <clinit> terminal-return detection ----------

    /**
     * The terminal `return-void` of a `<clinit>` — the one that would render as the last statement of the
     * companion `init { … }` block — or null. Only the terminal one is matched (last instruction of the
     * body's last leaf block), so an early conditional return-void stays rendered (no silent code loss).
     */
    private fun findClinitTerminalReturn(): Instruction? {
        if (method.name != CLASS_INIT_NAME) return null
        val block = lastBodyBlock() ?: return null
        val last = block.instructions.lastOrNull { !it.contains(AttrFlag.DONT_GENERATE) } ?: return null
        return if (last.opcode == IrOpcode.RETURN && last.argCount == 0) last else null
    }

    private fun lastBodyBlock(): BasicBlock? = when (val region = method.region) {
        null -> method.blocks.lastOrNull { it.instructions.isNotEmpty() }
        else -> lastLeafBlock(region)
    }

    private fun lastLeafBlock(container: IrContainer): BasicBlock? = when (container) {
        is BasicBlock -> container
        is SequenceRegion -> container.children.lastOrNull()?.let { lastLeafBlock(it) }
        else -> null
    }

    private companion object {
        const val CLASS_INIT_NAME = "<clinit>"

        /** `java.lang.invoke` support types synthesized for an invoke-custom call site. */
        val METHOD_HANDLES_TYPE = IrType.objectType("java.lang.invoke.MethodHandles")
        val METHOD_TYPE_TYPE = IrType.objectType("java.lang.invoke.MethodType")

        /** The only supertypes an array is assignable to; a named class outside this set can't hold one. */
        val ARRAY_SUPERTYPE_CLASSES = setOf(
            IrType.OBJECT_CLASS, "java.io.Serializable", "java.lang.Cloneable",
        )

        /**
         * Opcodes a hoisted `static final` initializer may inline through — only producers that are
         * genuinely **self-contained expressions**. Excludes `NEW_ARRAY` (a sized `new T[n]` filled by
         * separate `ARRAY_PUT`s outside this DAG — inlining would drop the elements) and `NEW_INSTANCE`
         * (an unfused allocation that renders an error marker); `CONSTRUCTOR`/`FILLED_NEW_ARRAY` carry
         * their arguments/elements inline and so ARE self-contained.
         */
        val INLINABLE_OPCODES = setOf(
            IrOpcode.CONST, IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS,
            IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG,
            IrOpcode.CAST, IrOpcode.CHECK_CAST, IrOpcode.ARITH, IrOpcode.NEG, IrOpcode.NOT,
            IrOpcode.INSTANCE_OF, IrOpcode.ARRAY_LENGTH, IrOpcode.ARRAY_GET,
            IrOpcode.FILLED_NEW_ARRAY, IrOpcode.STATIC_GET, IrOpcode.INSTANCE_GET,
            IrOpcode.INVOKE, IrOpcode.CONSTRUCTOR, IrOpcode.TERNARY, IrOpcode.STRING_CONCAT,
        )

        /**
         * Inlinable opcodes whose value is **order-sensitive**: they read mutable heap state (a field or
         * array element) or perform a call that may have a side effect / observe mutable state. A tree
         * containing any of these must not be hoisted ahead of a surviving `<clinit>` statement.
         */
        val ORDER_SENSITIVE_OPCODES = setOf(
            IrOpcode.STATIC_GET, IrOpcode.INSTANCE_GET, IrOpcode.ARRAY_GET,
            IrOpcode.INVOKE, IrOpcode.CONSTRUCTOR,
        )
    }
}
