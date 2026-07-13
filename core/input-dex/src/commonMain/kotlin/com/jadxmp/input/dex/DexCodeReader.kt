package com.jadxmp.input.dex

import com.jadxmp.input.CatchHandler
import com.jadxmp.input.CodeReader
import com.jadxmp.input.DebugInfo
import com.jadxmp.input.Instruction
import com.jadxmp.input.TryBlock

/**
 * Streams one method's `code_item`: register layout, instructions, try/catch table, and debug info.
 *
 * The `code_item` header is a fixed 16-byte prologue followed by the instruction array, optional
 * padding, the try table, and the catch-handler list — all parsed lazily from [codeItemOffset] so a
 * method body costs nothing until visited.
 *
 * jadx: DexCodeReader
 */
internal class DexCodeReader(
    private val dex: Dex,
    private val codeItemOffset: Int,
    private val methodIdx: Int,
) : CodeReader {

    override val registerCount: Int get() = dex.cursor(codeItemOffset).readU16()

    override val unitsCount: Int get() = dex.cursor(codeItemOffset + 12).readS32()

    override val codeOffset: Int get() = codeItemOffset

    override fun visitInstructions(visitor: (Instruction) -> Unit) {
        val insnsSize = dex.cursor(codeItemOffset + 12).readS32()
        val code = dex.cursor(codeItemOffset + 16)
        val insn = DexInstruction(dex, code)
        var offset = 0
        while (offset < insnsSize) {
            val insnStart = code.position
            val opcodeUnit = code.readU16()
            val info = DexOpcodeTable.lookup(opcodeUnit)
            insn.reset(insnStart, offset, opcodeUnit, info)
            visitor(insn)
            if (!insn.decoded) insn.skip()
            offset += insn.length
        }
    }

    override val tries: List<TryBlock>
        get() {
            val triesCount = dex.cursor(codeItemOffset + 6).readU16()
            if (triesCount == 0) return emptyList()
            val insnsCount = unitsCount
            val padding = if (insnsCount % 2 == 1) 2 else 0
            val triesAbs = codeItemOffset + 16 + insnsCount * 2 + padding
            val handlersAbs = triesAbs + 8 * triesCount
            val handlers = parseCatchHandlers(handlersAbs)

            val c = dex.cursor(triesAbs)
            val list = ArrayList<TryBlock>(Bounds.capacity(triesCount, stride = 8, reader = c))
            repeat(triesCount) {
                val startAddr = c.readS32()
                val count = c.readU16()
                val handlerOff = c.readU16()
                val handler = handlers[handlerOff]
                    ?: throw com.jadxmp.io.ByteReaderException("catch handler not found at $handlerOff")
                list.add(DexTryBlock(startAddr, startAddr + count - 1, handler))
            }
            return list
        }

    override val debugInfo: DebugInfo?
        get() {
            val debugOff = dex.cursor(codeItemOffset + 8).readS32()
            if (debugOff == 0) return null
            return DebugInfoParser(dex, registerCount, unitsCount)
                .parse(debugOff, dex.methodParamTypes(methodIdx))
        }

    /** Parse the `encoded_catch_handler_list`, keyed by each handler's byte offset within the list. */
    private fun parseCatchHandlers(absPos: Int): Map<Int, CatchHandler> {
        val c = dex.cursor(absPos)
        val size = c.readUleb128().toInt()
        val map = HashMap<Int, CatchHandler>(Bounds.capacity(size, stride = 1, reader = c))
        repeat(size) {
            val byteIndex = c.position - absPos
            val sizeAndType = c.readSleb128()
            val handlersLen = if (sizeAndType < 0) -sizeAndType else sizeAndType
            // Each type/addr pair is two ulebs (>= 2 bytes); clamp against remaining before allocating.
            val cap = Bounds.capacity(handlersLen, stride = 2, reader = c)
            val types = ArrayList<String>(cap)
            val addrs = ArrayList<Int>(cap)
            repeat(handlersLen) {
                val typeIdx = c.readUleb128().toInt()
                val addr = c.readUleb128().toInt()
                types.add(dex.type(typeIdx) ?: "?")
                addrs.add(addr)
            }
            val catchAll = if (sizeAndType <= 0) c.readUleb128().toInt() else -1
            map[byteIndex] = DexCatchHandler(types, addrs, catchAll)
        }
        return map
    }
}
