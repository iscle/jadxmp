package com.jadxmp.input

/**
 * One `catch` clause set guarding a [TryBlock].
 *
 * [types] and [handlers] are parallel: `handlers[i]` is the code offset (in code units) that catches
 * exceptions of type `types[i]`. [catchAllHandler] is the offset of a `finally`/`catch-all` handler,
 * or -1 if there is none.
 *
 * jadx: ICatch
 */
public interface CatchHandler {
    public val types: List<String>

    public val handlers: List<Int>

    public val catchAllHandler: Int
}

/**
 * A protected instruction range and the handler that guards it. Offsets are in code units, inclusive.
 *
 * jadx: ITry
 */
public interface TryBlock {
    public val startOffset: Int

    public val endOffset: Int

    public val catchHandler: CatchHandler
}
