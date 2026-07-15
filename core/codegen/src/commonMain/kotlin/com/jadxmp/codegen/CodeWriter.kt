package com.jadxmp.codegen

/**
 * Appends source text while recording, per character offset, the [CodeAnnotation] metadata that powers
 * the GUI's highlighting and jump-to-definition. **jadx: ICodeWriter / CodeWriter**
 *
 * The two outputs — text and metadata — are produced in one pass, so an annotation attached with
 * [attach] lands at exactly the offset of the token written next. That is the whole contract: call an
 * `attach…` right before emitting the token it describes.
 *
 * ### Determinism
 * Output is fully deterministic: a fixed line separator (`\n`) and a fixed [indentUnit] (four spaces),
 * indentation emitted lazily so blank lines carry no trailing whitespace, and a stable metadata order.
 * This keeps generated diffs and the accuracy oracle's textual comparison stable.
 *
 * Not thread-safe: a writer is used by the single coroutine generating one file.
 */
class CodeWriter(
    private val indentUnit: String = INDENT_UNIT,
    private val lineSeparator: String = "\n",
) {
    private val out = StringBuilder()
    private var indentLevel = 0

    // True when the cursor sits at the start of a fresh line and its indentation has not been emitted
    // yet. Indentation is flushed only when the first real content of the line arrives, so an empty
    // line (two [newLine]s in a row) never gets trailing spaces.
    private var lineStartPending = true
    private var currentLine = 1

    // Last write per offset wins (mirrors jadx's map semantics).
    private val annotations = LinkedHashMap<Int, CodeAnnotation>()
    private val lineToBytecode = LinkedHashMap<Int, Int>()

    /** Current character offset (== the length of the text emitted so far). */
    val length: Int get() = out.length

    /** Current (1-based) source line number. */
    val line: Int get() = currentLine

    val indent: Int get() = indentLevel

    private fun flushIndent() {
        if (lineStartPending) {
            lineStartPending = false
            if (indentLevel > 0) {
                repeat(indentLevel) { out.append(indentUnit) }
            }
        }
    }

    /** Append [text]. Must not contain a line separator — use [newLine] for line breaks. */
    fun add(text: String): CodeWriter {
        if (text.isEmpty()) return this
        flushIndent()
        out.append(text)
        return this
    }

    fun add(ch: Char): CodeWriter {
        flushIndent()
        out.append(ch)
        return this
    }

    /** Emit a line break; the next content starts a new (indented) line. */
    fun newLine(): CodeWriter {
        out.append(lineSeparator)
        currentLine++
        lineStartPending = true
        return this
    }

    fun incIndent(): CodeWriter {
        indentLevel++
        return this
    }

    fun decIndent(): CodeWriter {
        require(indentLevel > 0) { "indent underflow" }
        indentLevel--
        return this
    }

    inline fun indented(block: CodeWriter.() -> Unit): CodeWriter {
        incIndent()
        block()
        decIndent()
        return this
    }

    // ---- rule-4 fault-isolation checkpoint ----

    /**
     * An opaque snapshot of the writer's full mutable state ([checkpoint]), rolled back by [restore].
     * This is the CodeWriter half of the codegen backends' per-member fault barrier (CLAUDE rule 4): if
     * rendering ONE member throws — an unrenderable instruction, an out-of-bounds operand access, or the
     * StackOverflowError a pathological single-use inline chain can trigger — the member's partially
     * emitted text AND its offset→annotation metadata AND the indent/line state are rolled back to the
     * point BEFORE the member, so the writer is pristine for an honest error marker and the FOLLOWING
     * members render uncorrupted. jadx does the narrower `code.setIndent(savedIndent)` in
     * `ClassGen.addMethod`; we roll back the whole fragment so it never reaches the output or its metadata
     * (a half-emitted method that opened a brace but never wrote its matching NodeEnd would otherwise
     * corrupt the brace-nesting model `nodeAt` relies on, and leave annotations past the truncated text).
     */
    class Checkpoint internal constructor(
        internal val length: Int,
        internal val indentLevel: Int,
        internal val lineStartPending: Boolean,
        internal val currentLine: Int,
    )

    /** Snapshot the writer so a later [restore] can roll a failed member's partial output back. Emits nothing. */
    fun checkpoint(): Checkpoint = Checkpoint(out.length, indentLevel, lineStartPending, currentLine)

    /**
     * Roll the writer back to [checkpoint]: truncate the emitted text, restore the indent / line / pending-
     * line-start state, and drop every annotation and line→bytecode mapping recorded after the checkpoint
     * (their offsets and lines no longer exist). Emits nothing; leaves the writer exactly as it was at
     * [checkpoint] so an error marker can be written at the failed member's position.
     */
    fun restore(checkpoint: Checkpoint) {
        out.setLength(checkpoint.length)
        indentLevel = checkpoint.indentLevel
        lineStartPending = checkpoint.lineStartPending
        currentLine = checkpoint.currentLine
        // Offsets are absolute, so any annotation at or past the truncated length belongs to the rolled-back
        // fragment. A line the checkpoint had not yet started (lineStartPending) is wholly the fragment's, so
        // its bytecode mapping is dropped too; a line already begun keeps whatever earlier mapping it had.
        annotations.keys.removeAll { it >= checkpoint.length }
        val firstDroppedLine = if (checkpoint.lineStartPending) checkpoint.currentLine else checkpoint.currentLine + 1
        lineToBytecode.keys.removeAll { it >= firstDroppedLine }
    }

    // ---- metadata ----

    /**
     * Record [annotation] at the current offset (the position the next emitted character will occupy).
     * Any pending indentation is flushed first so the recorded offset matches the token's real start.
     */
    fun attach(annotation: CodeAnnotation): CodeWriter {
        flushIndent()
        annotations[out.length] = annotation
        return this
    }

    fun attachDefinition(ref: CodeNodeRef): CodeWriter = attach(DefinitionAnnotation(ref))

    fun attachReference(ref: CodeNodeRef): CodeWriter = attach(ReferenceAnnotation(ref))

    fun attachVariable(ref: VarRef, declaration: Boolean): CodeWriter = attach(VariableAnnotation(ref, declaration))

    fun attachNodeEnd(): CodeWriter = attach(NodeEndAnnotation)

    fun attachBytecodeOffset(bytecodeOffset: Int): CodeWriter = attach(BytecodeOffsetAnnotation(bytecodeOffset))

    /** Record that the current source line originates from bytecode [bytecodeOffset]. */
    fun mapLineToBytecode(bytecodeOffset: Int): CodeWriter {
        lineToBytecode[currentLine] = bytecodeOffset
        return this
    }

    /** Convenience: attach a reference, then emit its display [name]. */
    fun reference(ref: CodeNodeRef, name: String): CodeWriter {
        attachReference(ref)
        return add(name)
    }

    /** Convenience: attach a definition, then emit its display [name]. */
    fun definition(ref: CodeNodeRef, name: String): CodeWriter {
        attachDefinition(ref)
        return add(name)
    }

    /** Convenience: attach a variable occurrence, then emit its [VarRef.name]. */
    fun variable(ref: VarRef, declaration: Boolean): CodeWriter {
        attachVariable(ref, declaration)
        return add(ref.name)
    }

    /** The text produced so far (without finalizing metadata). Mostly for debugging. */
    fun currentText(): String = out.toString()

    /** Finalize into an immutable [CodeInfo]. The writer may still be appended to afterward. */
    fun finish(): CodeInfo = CodeInfo(out.toString(), CodeMetadata.build(annotations, lineToBytecode))

    companion object {
        /** Four spaces, matching jadx's default and the corpus expectations. */
        const val INDENT_UNIT: String = "    "
    }
}
