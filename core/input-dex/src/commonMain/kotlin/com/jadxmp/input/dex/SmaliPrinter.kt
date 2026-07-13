package com.jadxmp.input.dex

import com.jadxmp.input.AccessFlags
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeReader
import com.jadxmp.input.FieldData
import com.jadxmp.input.FillArrayDataPayload
import com.jadxmp.input.IndexType
import com.jadxmp.input.Instruction
import com.jadxmp.input.MethodData
import com.jadxmp.input.Opcode
import com.jadxmp.input.SwitchPayload
import com.jadxmp.input.TryBlock

/**
 * Serializes a parsed [ClassData] to baksmali-style smali text for the "show bytecode" view.
 *
 * This is a **display** disassembler, not an assembler round-trip: it emits valid, baksmali-faithful
 * structural smali (`.class`/`.super`/`.implements`/`.source`, `.field` lines, `.method` blocks with
 * `.registers`, the instruction list with `vX` registers, `L…;->name:Type` / `L…;->m(args)ret`
 * references, signed-hex literals, `:label` branch/switch targets, `.packed-switch`/`.sparse-switch`/
 * `.array-data` payload blocks, and `.catch`/`.catchall` for try blocks). It favors robustness over
 * total fidelity: a method whose body cannot be decoded degrades to a comment (rule 4, no code loss of
 * the surrounding class), and an unmapped opcode is emitted as a clearly-marked placeholder rather than
 * a wrong mnemonic.
 *
 * Deferred (does NOT affect smali validity): debug info (`.line`, `.local`, `.param`), annotations
 * (`.annotation` blocks), and full `invoke-custom`/`const-method-handle` call-site rendering (emitted
 * as a marked best-effort reference). These are display niceties layered on top of correct structure.
 *
 * Pure (model → String) and wasm-safe: only the `core:input` SPI is touched, no platform APIs.
 *
 * Registers are always printed in the `vN` form (never the `pN` parameter alias baksmali also accepts);
 * both are valid smali and `vN` needs no ins/outs bookkeeping, so it is the deliberate choice here.
 */
internal object SmaliPrinter {

    fun render(cls: ClassData): String {
        val sb = StringBuilder()
        sb.append(".class ")
        appendClassAccess(sb, cls.accessFlags)
        sb.append(cls.type).append('\n')
        cls.superType?.let { sb.append(".super ").append(it).append('\n') }
        for (iface in cls.interfaces) sb.append(".implements ").append(iface).append('\n')
        cls.sourceFile?.let { sb.append(".source \"").append(escape(it)).append("\"\n") }

        if (cls.fields.isNotEmpty()) sb.append('\n')
        for (f in cls.fields) renderField(sb, f)

        for (m in cls.methods) {
            sb.append('\n')
            // Fault isolation (rule 4): one undecodable method must not corrupt the class view. Render into
            // a LOCAL builder and splice it in only on success; on failure emit a self-contained, still-valid
            // `.method … .end method` error stub instead. Rendering into `sb` directly and truncating on
            // error would work too, but a local builder can't leave a half-written block behind even if the
            // guarded region grows — everything that can throw (including `code.registerCount` and the
            // per-instruction `decode()`) is inside the try below.
            val block = StringBuilder()
            try {
                renderMethod(block, m)
                sb.append(block)
            } catch (e: Exception) {
                sb.append(".method ").append(methodSignature(m)).append('\n')
                sb.append("    # <error rendering method: ").append(e.message).append(">\n")
                sb.append(".end method\n")
            }
        }
        return sb.toString()
    }

    private fun renderField(sb: StringBuilder, f: FieldData) {
        sb.append(".field ")
        appendFieldAccess(sb, f.accessFlags)
        sb.append(f.name).append(':').append(f.type)
        f.constValue?.let { sb.append(" = ").append(encodedValueText(it.value)) }
        sb.append('\n')
    }

    private fun renderMethod(sb: StringBuilder, m: MethodData) {
        sb.append(".method ")
        appendMethodAccess(sb, m.accessFlags)
        sb.append(methodSignature(m)).append('\n')
        val code = m.codeReader
        if (code != null) {
            sb.append("    .registers ").append(code.registerCount).append('\n')
            renderBody(sb, code)
        }
        sb.append(".end method\n")
    }

    private fun methodSignature(m: MethodData): String {
        val ref = m.ref
        return buildString {
            append(ref.name).append('(')
            for (p in ref.parameterTypes) append(p)
            append(')').append(ref.returnType)
        }
    }

    // ---- method body -----------------------------------------------------------------------------

    private fun renderBody(sb: StringBuilder, code: CodeReader) {
        val body = collectLabels(code)
        val labels = body.labels
        val emitted = HashSet<Int>()
        code.visitInstructions { insn ->
            insn.decode()
            val off = insn.offset
            emitLabelsAt(sb, labels, off, emitted)
            if (isPayload(insn.opcode)) {
                renderPayload(sb, insn, body.payloadToSwitch)
            } else {
                sb.append("    ").append(renderInsn(insn)).append('\n')
            }
        }
        // Trailing labels/directives whose offset sits at (or past) the end of the instruction stream —
        // most commonly a `:try_end_*` that closes a try block covering the final instruction.
        for (off in labels.keys.filter { it !in emitted }.sorted()) {
            emitLabelsAt(sb, labels, off, emitted)
        }
    }

    private fun emitLabelsAt(sb: StringBuilder, labels: Map<Int, LabelSet>, off: Int, emitted: MutableSet<Int>) {
        if (off in emitted) return
        val set = labels[off] ?: return
        emitted.add(off)
        for (name in set.names.sorted()) sb.append("    :").append(name).append('\n')
        for (directive in set.catchDirectives) sb.append("    ").append(directive).append('\n')
    }

    /**
     * Two-pass label collection: the first pass over the instruction stream records every branch/switch
     * target and try/catch boundary as a label definition keyed by its code-unit offset, so the second
     * (rendering) pass can emit each label immediately before its target instruction. Doing it up front is
     * what lets a forward branch reference a label that is only defined later in the stream.
     */
    private fun collectLabels(code: CodeReader): BodyLabels {
        val labels = HashMap<Int, LabelSet>()
        val payloadToSwitch = HashMap<Int, Int>()
        val switchPayloads = ArrayList<SwitchPayloadRec>()

        fun def(offset: Int, prefix: String) {
            labels.getOrPut(offset) { LabelSet() }.names.add(labelName(prefix, offset))
        }

        code.visitInstructions { insn ->
            insn.decode()
            when (insn.opcode) {
                Opcode.GOTO -> def(insn.target, "goto")
                Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
                Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ ->
                    def(insn.target, "cond")
                // Note: if two switches somehow share one payload offset (vanishingly rare — the compiler
                // emits a distinct payload per switch), only the last switch's base is kept, so the other's
                // case labels could be off. Not worth a multimap for a case real dex never produces.
                Opcode.PACKED_SWITCH -> {
                    def(insn.target, "pswitch_data")
                    payloadToSwitch[insn.target] = insn.offset
                }
                Opcode.SPARSE_SWITCH -> {
                    def(insn.target, "sswitch_data")
                    payloadToSwitch[insn.target] = insn.offset
                }
                Opcode.FILL_ARRAY_DATA -> def(insn.target, "array")
                Opcode.PACKED_SWITCH_PAYLOAD ->
                    (insn.payload as? SwitchPayload)?.let { switchPayloads.add(SwitchPayloadRec(insn.offset, it.targets, true)) }
                Opcode.SPARSE_SWITCH_PAYLOAD ->
                    (insn.payload as? SwitchPayload)?.let { switchPayloads.add(SwitchPayloadRec(insn.offset, it.targets, false)) }
                else -> {}
            }
        }
        // Switch case targets are stored relative to the *switch* instruction, so they can only be resolved
        // to absolute offsets once both the switch and its payload have been seen.
        for (rec in switchPayloads) {
            val switchOff = payloadToSwitch[rec.offset] ?: continue
            val prefix = if (rec.packed) "pswitch" else "sswitch"
            for (t in rec.targets) def(switchOff + t, prefix)
        }
        collectTryLabels(code, labels)
        return BodyLabels(labels, payloadToSwitch)
    }

    private fun collectTryLabels(code: CodeReader, labels: HashMap<Int, LabelSet>) {
        val tries = try {
            code.tries
        } catch (e: Exception) {
            return // a malformed try table must not sink the whole body
        }
        for (t in tries) {
            val startOff = t.startOffset
            val endOff = t.endOffset + 1 // exclusive boundary in code units (endOffset is the last covered unit)
            labels.getOrPut(startOff) { LabelSet() }.names.add(labelName("try_start", startOff))
            val endSet = labels.getOrPut(endOff) { LabelSet() }
            endSet.names.add(labelName("try_end", endOff))
            val handler = t.catchHandler
            val startLabel = ":" + labelName("try_start", startOff)
            val endLabel = ":" + labelName("try_end", endOff)
            for (i in handler.types.indices) {
                val hOff = handler.handlers.getOrNull(i) ?: continue
                labels.getOrPut(hOff) { LabelSet() }.names.add(labelName("catch", hOff))
                endSet.catchDirectives.add(
                    ".catch ${handler.types[i]} {$startLabel .. $endLabel} :${labelName("catch", hOff)}",
                )
            }
            if (handler.catchAllHandler >= 0) {
                val hOff = handler.catchAllHandler
                labels.getOrPut(hOff) { LabelSet() }.names.add(labelName("catchall", hOff))
                endSet.catchDirectives.add(
                    ".catchall {$startLabel .. $endLabel} :${labelName("catchall", hOff)}",
                )
            }
        }
    }

    private fun renderInsn(insn: Instruction): String {
        val op = insn.opcode
        // An unmapped opcode is emitted as a FULL comment line (no bare mnemonic token, which would be an
        // invalid instruction that fails to reassemble) — a clearly-marked placeholder, never a wrong op.
        if (op == Opcode.UNKNOWN) {
            return "# unmapped opcode 0x" + insn.rawOpcodeUnit.toString(16) + " (" + insn.mnemonic + ")"
        }
        val sb = StringBuilder(insn.mnemonic)
        when {
            op == Opcode.GOTO -> sb.append(' ').append(refLabel("goto", insn.target))
            isConditional(op) -> {
                appendRegs(sb, insn)
                sb.append(", ").append(refLabel("cond", insn.target))
            }
            op == Opcode.PACKED_SWITCH -> {
                appendRegs(sb, insn); sb.append(", ").append(refLabel("pswitch_data", insn.target))
            }
            op == Opcode.SPARSE_SWITCH -> {
                appendRegs(sb, insn); sb.append(", ").append(refLabel("sswitch_data", insn.target))
            }
            op == Opcode.FILL_ARRAY_DATA -> {
                appendRegs(sb, insn); sb.append(", ").append(refLabel("array", insn.target))
            }
            isInvokeShaped(op) -> renderInvokeShaped(sb, insn)
            else -> renderGeneral(sb, insn)
        }
        return sb.toString()
    }

    /** invoke-* and filled-new-array-* carry a brace-wrapped register list. */
    private fun renderInvokeShaped(sb: StringBuilder, insn: Instruction) {
        sb.append(' ')
        appendBraceRegs(sb, insn)
        val ref = when (insn.indexType) {
            IndexType.METHOD_REF -> safe { methodRefText(insn) }
            IndexType.TYPE_REF -> safe { insn.indexAsType() }
            IndexType.CALL_SITE -> "# call-site (invoke-custom deferred)"
            else -> null
        }
        if (ref != null) sb.append(", ").append(ref)
        // invoke-polymorphic fuses the proto index into the branch-target slot.
        if (insn.opcode == Opcode.INVOKE_POLYMORPHIC || insn.opcode == Opcode.INVOKE_POLYMORPHIC_RANGE) {
            sb.append(", ").append(safe { protoText(insn.indexAsProto(insn.target)) })
        }
    }

    private fun renderGeneral(sb: StringBuilder, insn: Instruction) {
        appendRegs(sb, insn)
        val op = insn.opcode
        when (insn.indexType) {
            IndexType.STRING_REF -> sb.append(", \"").append(escape(safe { insn.indexAsString() })).append('"')
            IndexType.TYPE_REF -> sb.append(", ").append(safe { insn.indexAsType() })
            IndexType.FIELD_REF -> sb.append(", ").append(safe { fieldRefText(insn) })
            IndexType.METHOD_REF -> sb.append(", ").append(safe { methodRefText(insn) })
            IndexType.PROTO_REF -> sb.append(", ").append(safe { protoText(insn.indexAsProto(insn.index)) })
            IndexType.METHOD_HANDLE_REF -> sb.append(", # method-handle (deferred)")
            IndexType.CALL_SITE -> sb.append(", # call-site (deferred)")
            IndexType.NONE -> if (hasLiteral(op)) sb.append(", ").append(litHex(insn.literal, op == Opcode.CONST_WIDE))
        }
    }

    private fun renderPayload(sb: StringBuilder, insn: Instruction, payloadToSwitch: Map<Int, Int>) {
        when (val payload = insn.payload) {
            is SwitchPayload -> renderSwitchPayload(sb, insn, payload, payloadToSwitch)
            is FillArrayDataPayload -> renderArrayPayload(sb, payload)
            else -> sb.append("    # <payload>\n")
        }
    }

    private fun renderSwitchPayload(
        sb: StringBuilder,
        insn: Instruction,
        payload: SwitchPayload,
        payloadToSwitch: Map<Int, Int>,
    ) {
        val packed = insn.opcode == Opcode.PACKED_SWITCH_PAYLOAD
        val prefix = if (packed) "pswitch" else "sswitch"
        // Case targets are relative to the switch instruction (recorded during label collection). Recover
        // the switch offset so absolute case-target labels match the inline definitions.
        val switchOff = payloadToSwitch[insn.offset]
        fun caseLabel(i: Int): String =
            if (switchOff == null) "# orphan-switch-target" else refLabel(prefix, switchOff + payload.targets[i])
        if (packed) {
            sb.append("    .packed-switch ").append(litHex(payload.keys.firstOrNull()?.toLong() ?: 0L, false)).append('\n')
            for (i in payload.targets.indices) sb.append("        ").append(caseLabel(i)).append('\n')
            sb.append("    .end packed-switch\n")
        } else {
            sb.append("    .sparse-switch\n")
            for (i in payload.targets.indices) {
                sb.append("        ").append(litHex(payload.keys[i].toLong(), false)).append(" -> ").append(caseLabel(i)).append('\n')
            }
            sb.append("    .end sparse-switch\n")
        }
    }

    // ---- register / operand formatting -----------------------------------------------------------

    private fun appendRegs(sb: StringBuilder, insn: Instruction) {
        for (i in 0 until insn.registerCount) {
            sb.append(if (i == 0) " " else ", ").append('v').append(insn.register(i))
        }
    }

    private fun appendBraceRegs(sb: StringBuilder, insn: Instruction) {
        val rc = insn.registerCount
        if (isRange(insn.opcode)) {
            if (rc == 0) {
                sb.append("{}")
            } else {
                sb.append('{').append('v').append(insn.register(0)).append(" .. v").append(insn.register(rc - 1)).append('}')
            }
        } else {
            sb.append('{')
            for (i in 0 until rc) {
                if (i != 0) sb.append(", ")
                sb.append('v').append(insn.register(i))
            }
            sb.append('}')
        }
    }

    private fun fieldRefText(insn: Instruction): String {
        val f = insn.indexAsField()
        return "${f.declaringClassType}->${f.name}:${f.type}"
    }

    private fun methodRefText(insn: Instruction): String {
        val m = insn.indexAsMethod()
        return buildString {
            append(m.declaringClassType).append("->").append(m.name).append('(')
            for (p in m.parameterTypes) append(p)
            append(')').append(m.returnType)
        }
    }

    private fun protoText(p: com.jadxmp.input.MethodProto): String = buildString {
        append('(')
        for (t in p.parameterTypes) append(t)
        append(')').append(p.returnType)
    }

    private fun renderArrayPayload(sb: StringBuilder, payload: FillArrayDataPayload) {
        sb.append("    .array-data ").append(payload.elementSize).append('\n')
        val suffix = when (payload.elementSize) {
            1 -> "t"
            2 -> "s"
            8 -> "L"
            else -> ""
        }
        when (val data = payload.data) {
            is ByteArray -> for (v in data) sb.append("        ").append(litHex(v.toLong(), false)).append(suffix).append('\n')
            is ShortArray -> for (v in data) sb.append("        ").append(litHex(v.toLong(), false)).append(suffix).append('\n')
            is IntArray -> for (v in data) sb.append("        ").append(litHex(v.toLong(), false)).append(suffix).append('\n')
            is LongArray -> for (v in data) sb.append("        ").append(litHex(v, false)).append(suffix).append('\n')
        }
        sb.append("    .end array-data\n")
    }

    // ---- small utilities -------------------------------------------------------------------------

    private fun labelName(prefix: String, offset: Int): String = prefix + "_" + offset.toString(16)
    private fun refLabel(prefix: String, offset: Int): String = ":" + labelName(prefix, offset)

    private fun isConditional(op: Opcode): Boolean = when (op) {
        Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
        Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ -> true
        else -> false
    }

    private fun isInvokeShaped(op: Opcode): Boolean = op.name.startsWith("INVOKE_") ||
        op == Opcode.FILLED_NEW_ARRAY || op == Opcode.FILLED_NEW_ARRAY_RANGE

    private fun isRange(op: Opcode): Boolean = op.name.endsWith("_RANGE")

    private fun isPayload(op: Opcode): Boolean = when (op) {
        Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.FILL_ARRAY_DATA_PAYLOAD -> true
        else -> false
    }

    private fun hasLiteral(op: Opcode): Boolean = when (op) {
        Opcode.CONST, Opcode.CONST_WIDE,
        Opcode.ADD_INT_LIT, Opcode.RSUB_INT, Opcode.MUL_INT_LIT, Opcode.DIV_INT_LIT, Opcode.REM_INT_LIT,
        Opcode.AND_INT_LIT, Opcode.OR_INT_LIT, Opcode.XOR_INT_LIT,
        Opcode.SHL_INT_LIT, Opcode.SHR_INT_LIT, Opcode.USHR_INT_LIT -> true
        else -> false
    }

    private fun litHex(v: Long, wide: Boolean): String {
        val body = when {
            v == Long.MIN_VALUE -> "-0x8000000000000000"
            v < 0 -> "-0x" + (-v).toString(16)
            else -> "0x" + v.toString(16)
        }
        return if (wide) body + "L" else body
    }

    private fun encodedValueText(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + escape(value) + "\""
        is Boolean -> value.toString()
        is Byte -> litHex(value.toLong(), false)
        is Short -> litHex(value.toLong(), false)
        is Int -> litHex(value.toLong(), false)
        is Long -> litHex(value, true)
        is Char -> litHex(value.code.toLong(), false)
        else -> value.toString()
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private inline fun safe(block: () -> String): String = try {
        block()
    } catch (e: Exception) {
        "?"
    }

    // ---- access-flag rendering -------------------------------------------------------------------

    private fun appendClassAccess(sb: StringBuilder, flags: Int) {
        for ((bit, kw) in CLASS_FLAGS) if (AccessFlags.has(flags, bit)) sb.append(kw).append(' ')
    }

    private fun appendMethodAccess(sb: StringBuilder, flags: Int) {
        for ((bit, kw) in METHOD_FLAGS) if (AccessFlags.has(flags, bit)) sb.append(kw).append(' ')
    }

    private fun appendFieldAccess(sb: StringBuilder, flags: Int) {
        for ((bit, kw) in FIELD_FLAGS) if (AccessFlags.has(flags, bit)) sb.append(kw).append(' ')
    }

    // Ordered flag→keyword tables, context-specific because several bits are overloaded (0x20/0x40/0x80).
    private val CLASS_FLAGS = listOf(
        AccessFlags.PUBLIC to "public",
        AccessFlags.FINAL to "final",
        AccessFlags.INTERFACE to "interface",
        AccessFlags.ABSTRACT to "abstract",
        AccessFlags.SYNTHETIC to "synthetic",
        AccessFlags.ANNOTATION to "annotation",
        AccessFlags.ENUM to "enum",
    )

    private val METHOD_FLAGS = listOf(
        AccessFlags.PUBLIC to "public",
        AccessFlags.PRIVATE to "private",
        AccessFlags.PROTECTED to "protected",
        AccessFlags.STATIC to "static",
        AccessFlags.FINAL to "final",
        AccessFlags.SYNCHRONIZED to "synchronized",
        AccessFlags.BRIDGE to "bridge",
        AccessFlags.VARARGS to "varargs",
        AccessFlags.NATIVE to "native",
        AccessFlags.ABSTRACT to "abstract",
        AccessFlags.STRICT to "strictfp",
        AccessFlags.SYNTHETIC to "synthetic",
        AccessFlags.CONSTRUCTOR to "constructor",
        AccessFlags.DECLARED_SYNCHRONIZED to "declared-synchronized",
    )

    private val FIELD_FLAGS = listOf(
        AccessFlags.PUBLIC to "public",
        AccessFlags.PRIVATE to "private",
        AccessFlags.PROTECTED to "protected",
        AccessFlags.STATIC to "static",
        AccessFlags.FINAL to "final",
        AccessFlags.VOLATILE to "volatile",
        AccessFlags.TRANSIENT to "transient",
        AccessFlags.SYNTHETIC to "synthetic",
        AccessFlags.ENUM to "enum",
    )

    /** The labels defined at one code-unit offset, plus any `.catch`/`.catchall` directives to emit there. */
    private class LabelSet {
        val names = LinkedHashSet<String>()
        val catchDirectives = ArrayList<String>()
    }

    private class SwitchPayloadRec(val offset: Int, val targets: IntArray, val packed: Boolean)

    /** Result of the label-collection pass: labels by offset, plus each switch payload's originating switch. */
    private class BodyLabels(val labels: Map<Int, LabelSet>, val payloadToSwitch: Map<Int, Int>)
}
