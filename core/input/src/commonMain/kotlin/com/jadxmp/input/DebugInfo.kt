package com.jadxmp.input

/**
 * A source-level local variable recovered from debug info, live over a code range.
 *
 * Offsets are code-unit offsets within the method. [isParameter] is a hint (from the debug stream)
 * that the variable is a method parameter; per the DEX spec it can be wrong and must not be trusted
 * for correctness, only for naming/heuristics.
 *
 * jadx: ILocalVar
 */
public interface LocalVar {
    public val registerNum: Int

    public val name: String?

    /** Type descriptor, or null if unknown. */
    public val type: String?

    /** Generic signature descriptor, if present. */
    public val signature: String?

    public val startOffset: Int

    public val endOffset: Int

    public val isParameter: Boolean
}

/**
 * Decoded debug information for a method: line numbers and local-variable table.
 *
 * jadx: IDebugInfo
 */
public interface DebugInfo {
    /** Maps instruction code-unit offset to source line number. */
    public val lineNumbers: Map<Int, Int>

    public val localVars: List<LocalVar>
}
