package com.jadxmp.input.dex

import com.jadxmp.input.DebugInfo
import com.jadxmp.input.LocalVar

/**
 * Interprets a method's `debug_info_item` — a little state machine that emits line-number entries and
 * tracks the live range of each local variable across the code.
 *
 * The opcodes below `DBG_FIRST_SPECIAL` are explicit (advance pc/line, start/end local); everything
 * from `0x0a` up is a "special" opcode that bumps both the address and the line in one byte. See the
 * DEX "Debug Info Item" spec.
 *
 * jadx: DebugInfoParser
 */
internal class DebugInfoParser(
    private val dex: Dex,
    private val registersCount: Int,
    private val codeSize: Int,
) {
    private val locals = arrayOfNulls<VarBuilder>(registersCount.coerceAtLeast(0))
    private val result = ArrayList<LocalVar>()
    private val lines = HashMap<Int, Int>()

    fun parse(debugOff: Int, argTypes: List<String>): DebugInfo {
        val input = dex.cursor(debugOff)
        var addr = 0
        var line = input.readUleb128().toInt()
        val paramsCount = input.readUleb128().toInt()

        val argRegs = computeArgRegs(argTypes)
        var varsFound = false

        for (i in 0 until paramsCount) {
            val nameId = input.readUleb128p1()
            val name = dex.string(nameId)
            if (name != null && i < argTypes.size) {
                val v = VarBuilder(argRegs[i], name, argTypes[i], null)
                startVar(v, addr)
                v.startOffset = PARAM_MARKER
                varsFound = true
            }
        }

        loop@ while (true) {
            when (val c = input.readU8()) {
                DBG_END_SEQUENCE -> break@loop
                DBG_ADVANCE_PC -> addr = advance(addr, input.readUleb128().toInt())
                DBG_ADVANCE_LINE -> line += input.readSleb128()
                DBG_START_LOCAL -> {
                    val reg = input.readUleb128().toInt()
                    val nameId = input.readUleb128().toInt() - 1
                    val typeId = input.readUleb128().toInt() - 1
                    startVar(VarBuilder(reg, dex.string(nameId), dex.type(typeId), null), addr)
                    varsFound = true
                }
                DBG_START_LOCAL_EXTENDED -> {
                    val reg = input.readUleb128().toInt()
                    val nameId = input.readUleb128p1()
                    val typeId = input.readUleb128p1()
                    val sigId = input.readUleb128p1()
                    startVar(VarBuilder(reg, dex.string(nameId), dex.type(typeId), dex.string(sigId)), addr)
                    varsFound = true
                }
                DBG_END_LOCAL -> {
                    val reg = input.readUleb128().toInt()
                    locals.getOrNull(reg)?.let { endVar(it, addr) }
                    varsFound = true
                }
                DBG_RESTART_LOCAL -> {
                    restartVar(input.readUleb128().toInt(), addr)
                    varsFound = true
                }
                DBG_SET_PROLOGUE_END, DBG_SET_EPILOGUE_BEGIN -> {}
                DBG_SET_FILE -> input.readUleb128p1() // source file: not needed for the model
                else -> {
                    val adjusted = c - DBG_FIRST_SPECIAL
                    addr = advance(addr, adjusted / DBG_LINE_RANGE)
                    line += DBG_LINE_BASE + adjusted % DBG_LINE_RANGE
                    lines[addr] = line
                }
            }
        }

        if (varsFound) {
            for (v in locals) {
                if (v != null && !v.isEnd) endVar(v, codeSize - 1)
            }
        }
        return DexDebugInfo(lines, result)
    }

    private fun computeArgRegs(argTypes: List<String>): IntArray {
        if (argTypes.isEmpty()) return IntArray(0)
        val regs = IntArray(argTypes.size)
        var regNum = registersCount
        for (i in argTypes.indices.reversed()) {
            regNum -= typeLen(argTypes[i])
            regs[i] = regNum
        }
        return regs
    }

    private fun advance(addr: Int, inc: Int): Int = minOf(addr + inc, codeSize - 1)

    private fun startVar(v: VarBuilder, addr: Int) {
        val reg = v.regNum
        if (reg < 0 || reg >= locals.size) return
        locals[reg]?.let { endVar(it, addr) }
        v.startOffset = addr
        v.isEnd = false
        locals[reg] = v
    }

    private fun endVar(v: VarBuilder, addr: Int) {
        if (v.isEnd) return
        v.isEnd = true
        v.endOffset = addr
        result.add(v.toLocalVar())
    }

    private fun restartVar(reg: Int, addr: Int) {
        val prev = locals.getOrNull(reg) ?: return
        endVar(prev, addr)
        startVar(VarBuilder(reg, prev.name, prev.type, prev.signature), addr)
    }

    private class VarBuilder(
        val regNum: Int,
        val name: String?,
        val type: String?,
        val signature: String?,
    ) {
        var startOffset = 0
        var endOffset = 0
        var isEnd = false

        fun toLocalVar(): LocalVar = DexLocalVar(
            registerNum = regNum,
            name = name,
            type = type,
            signature = signature,
            startOffset = startOffset,
            endOffset = endOffset,
            isParameter = startOffset == PARAM_MARKER,
        )
    }

    private companion object {
        const val DBG_END_SEQUENCE = 0x00
        const val DBG_ADVANCE_PC = 0x01
        const val DBG_ADVANCE_LINE = 0x02
        const val DBG_START_LOCAL = 0x03
        const val DBG_START_LOCAL_EXTENDED = 0x04
        const val DBG_END_LOCAL = 0x05
        const val DBG_RESTART_LOCAL = 0x06
        const val DBG_SET_PROLOGUE_END = 0x07
        const val DBG_SET_EPILOGUE_BEGIN = 0x08
        const val DBG_SET_FILE = 0x09
        const val DBG_FIRST_SPECIAL = 0x0a
        const val DBG_LINE_BASE = -4
        const val DBG_LINE_RANGE = 15
        const val PARAM_MARKER = -1

        fun typeLen(type: String): Int = when (type.firstOrNull()) {
            'J', 'D' -> 2
            else -> 1
        }
    }
}
