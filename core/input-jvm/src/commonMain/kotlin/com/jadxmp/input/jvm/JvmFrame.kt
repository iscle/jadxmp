package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException

/**
 * Bounded frame primitives for later JVM abstract execution (JVMS 4.10 and 6.5). Mutations validate
 * before changing state; snapshots own independent local/stack storage. This does not perform
 * hierarchy checks, instruction transfer, or StackMapTable verification.
 */
internal class JvmFrame(
    val maxLocals: Int,
    val maxStack: Int,
    thisUninitialized: Boolean = false,
) {
    init {
        if (maxLocals !in 0..65535 || maxStack !in 0..65535) {
            invalid("frame bounds must be unsigned 16-bit values")
        }
    }

    private val locals = MutableList<JvmLocalSlot>(maxLocals) { JvmLocalSlot.Top }
    private val operands = mutableListOf<JvmFrameValue>()

    /** Bottom to top, one entry per logical value. A returned snapshot cannot mutate this frame. */
    val stack: List<JvmFrameValue> get() = operands.toList()
    var stackWords: Int = 0
        private set

    /** Independent of aliases: overwriting local zero cannot make a constructor safe to return. */
    var thisUninitialized: Boolean = thisUninitialized
        private set

    fun slot(index: Int): JvmLocalSlot {
        checkLocal(index, 1)
        return locals[index]
    }

    fun local(index: Int): JvmFrameValue = when (val slot = slot(index)) {
        is JvmLocalSlot.Value -> slot.value
        else -> invalid("local $index is not a usable value")
    }

    fun store(index: Int, value: JvmFrameValue) {
        checkLocal(index, value.words)
        checkThis(value)
        // Invalidate entire old values intersected by the write, including a head before index.
        repeat(value.words) { invalidateLocal(index + it) }
        locals[index] = JvmLocalSlot.Value(value)
        if (value.words == 2) locals[index + 1] = JvmLocalSlot.Tail
    }

    fun push(value: JvmFrameValue) {
        checkThis(value)
        checkStack(stackWords + value.words)
        operands += value
        stackWords += value.words
    }

    fun pop(): JvmFrameValue {
        if (operands.isEmpty()) invalid("operand stack underflow")
        val value = operands.removeAt(operands.lastIndex)
        stackWords -= value.words
        return value
    }

    /**
     * Word groups enforce all category forms without treating half a long/double as a value.
     * Only the affected suffix (at most four input values) is copied, not the whole stack.
     */
    fun apply(operation: JvmStackOperation): JvmStackChange {
        val topWords = when (operation) {
            JvmStackOperation.POP2, JvmStackOperation.DUP2,
            JvmStackOperation.DUP2_X1, JvmStackOperation.DUP2_X2 -> 2
            else -> 1
        }
        val topStart = groupStart(operands.size, topWords)
        val belowWords = when (operation) {
            JvmStackOperation.SWAP, JvmStackOperation.DUP_X1, JvmStackOperation.DUP2_X1 -> 1
            JvmStackOperation.DUP_X2, JvmStackOperation.DUP2_X2 -> 2
            else -> 0
        }
        val start = groupStart(topStart, belowWords)
        val top = operands.subList(topStart, operands.size).toList()
        val below = operands.subList(start, topStart).toList()
        val replacement = when (operation) {
            JvmStackOperation.POP, JvmStackOperation.POP2 -> emptyList()
            JvmStackOperation.SWAP -> top + below
            else -> top + below + top
        }
        val newWords = stackWords - topWords - belowWords + replacement.sumOf { it.words }
        checkStack(newWords)
        val inputs = below + top
        val topIndices = (below.size until inputs.size).toList()
        val belowIndices = below.indices.toList()
        val output = when (operation) {
            JvmStackOperation.POP, JvmStackOperation.POP2 -> emptyList()
            JvmStackOperation.SWAP -> topIndices + belowIndices
            else -> topIndices + belowIndices + topIndices
        }
        val change = JvmStackChange(stackWords - topWords - belowWords, inputs, output)
        while (operands.size > start) operands.removeAt(operands.lastIndex)
        operands.addAll(replacement)
        stackWords = newWords
        return change
    }

    fun snapshot(): JvmFrame = JvmFrame(maxLocals, maxStack, thisUninitialized).also { copy ->
        for (index in locals.indices) copy.locals[index] = locals[index]
        copy.operands.addAll(operands)
        copy.stackWords = stackWords
    }

    /** Storage/scan cost used by bounded control-flow analysis; logical stack entries, not words. */
    val stateCells: Int get() = maxLocals + operands.size

    /**
     * Primitive control-flow join. Stack shapes/types must agree exactly; incompatible locals
     * become unusable. Reference ancestry joins remain a separate, unsupported analysis layer.
     * Validate the stack first so a rejected join never partly changes its destination.
     */
    fun mergeFrom(other: JvmFrame): Boolean {
        if (maxLocals != other.maxLocals || maxStack != other.maxStack) invalid("incompatible frame bounds")
        if (operands != other.operands) invalid("incompatible operand stacks at control-flow join")
        var changed = false
        for (index in locals.indices) {
            if (locals[index] != other.locals[index] && locals[index] != JvmLocalSlot.Top) {
                invalidateLocal(index)
                changed = true
            }
        }
        if (other.thisUninitialized && !thisUninitialized) {
            thisUninitialized = true
            changed = true
        }
        return changed
    }

    /**
     * Apply successful initialization after the caller validates and consumes the constructor
     * receiver. No live alias is required (NEW followed by an unused constructor call is legal).
     * Constructor owner/descriptor compatibility belongs to the instruction transfer validator.
     */
    fun initialize(target: JvmFrameValue.Uninitialized) {
        checkThis(target)
        val initialized = JvmFrameValue.Reference(target.descriptor)
        for (index in locals.indices) {
            if ((locals[index] as? JvmLocalSlot.Value)?.value == target) {
                locals[index] = JvmLocalSlot.Value(initialized)
            }
        }
        for (index in operands.indices) if (operands[index] == target) operands[index] = initialized
        if (target is JvmFrameValue.UninitializedThis) thisUninitialized = false
    }

    /**
     * Start from the PRE-instruction frame. Ordinary exceptions keep locals; failed constructors
     * make target aliases unusable so catch code cannot retry initialization. In both cases the
     * stack becomes exactly the caught reference and the receiver-initialization flag is retained.
     */
    fun exceptional(
        caught: JvmFrameValue.Reference,
        failedConstructor: JvmFrameValue.Uninitialized? = null,
    ): JvmFrame {
        if (failedConstructor != null) checkThis(failedConstructor)
        checkStack(1)
        return snapshot().also { copy ->
            if (failedConstructor != null) {
                for (index in copy.locals.indices) {
                    if ((copy.locals[index] as? JvmLocalSlot.Value)?.value == failedConstructor) {
                        copy.locals[index] = JvmLocalSlot.Top
                    }
                }
            }
            copy.operands.clear()
            copy.operands += caught
            copy.stackWords = 1
        }
    }

    fun requireInitializedThis() {
        if (thisUninitialized) invalid("constructor receiver is not initialized")
    }

    private fun groupStart(end: Int, words: Int): Int {
        var start = end
        var remaining = words
        while (remaining > 0) {
            if (start == 0) invalid("operand stack underflow")
            remaining -= operands[--start].words
        }
        if (remaining != 0) invalid("illegal operand category for stack operation")
        return start
    }

    private fun invalidateLocal(index: Int) {
        when (val old = locals[index]) {
            JvmLocalSlot.Tail -> locals[index - 1] = JvmLocalSlot.Top
            is JvmLocalSlot.Value -> if (old.value.words == 2) locals[index + 1] = JvmLocalSlot.Top
            JvmLocalSlot.Top -> Unit
        }
        locals[index] = JvmLocalSlot.Top
    }

    private fun checkLocal(index: Int, words: Int) {
        if (index < 0 || index > maxLocals - words) invalid("local access $index/$words exceeds max_locals $maxLocals")
    }

    private fun checkStack(words: Int) {
        if (words > maxStack) invalid("operand stack exceeds max_stack $maxStack")
    }

    private fun checkThis(value: JvmFrameValue) {
        if (value is JvmFrameValue.UninitializedThis && !thisUninitialized) {
            invalid("uninitialized receiver in an initialized frame")
        }
    }

    private fun invalid(message: String): Nothing = throw ByteReaderException("invalid JVM frame: $message")
}
