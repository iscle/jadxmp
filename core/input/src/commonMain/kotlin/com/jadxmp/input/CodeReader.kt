package com.jadxmp.input

/**
 * Streams the body of a single method: its register layout, instructions, try/catch table, and
 * (optionally) debug info. Obtained lazily from [MethodData.codeReader] so method bodies are only
 * decoded when actually needed.
 *
 * jadx: ICodeReader
 */
public interface CodeReader {
    /** Total registers used by the method frame. */
    public val registerCount: Int

    /** Size of the instruction array in 16-bit code units. */
    public val unitsCount: Int

    /** Absolute byte offset of the code item in the input file. */
    public val codeOffset: Int

    /**
     * Visit each instruction in order. The [Instruction] passed to [visitor] is reused across calls;
     * see the streaming contract on [Instruction].
     */
    public fun visitInstructions(visitor: (Instruction) -> Unit)

    public val tries: List<TryBlock>

    public val debugInfo: DebugInfo?
}
