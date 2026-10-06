package com.jadxmp.pipeline.types

import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Consumer-specific views of untyped numeric constants, before inference writes back operand types.
 * Only constants and exact copies qualify. A phi qualifies only if every incoming path proves the
 * same bits and width; cycles remain unknown. This never interprets a computed numeric conversion
 * as a bit reinterpretation. Memoized iterative traversal bounds work by the SSA graph size.
 */
internal class RawConstantViews(private val cancellation: CancellationCheck = CancellationCheck.None) {
    private data class Bits(val value: Long, val wide: Boolean)
    private class Frame(val value: SsaValue, val sources: List<SsaValue>, var next: Int = 0)
    private val cache = HashMap<SsaValue, Bits?>()

    fun supports(use: RegisterOperand, target: IrType): Boolean = view(use, target) != null

    fun literal(use: RegisterOperand, target: IrType): LiteralOperand? =
        view(use, target)?.let { LiteralOperand(it.value, target) }

    private fun view(use: RegisterOperand, target: IrType): Bits? {
        val wide = when (target) {
            IrType.INT, IrType.FLOAT -> false
            IrType.LONG, IrType.DOUBLE -> true
            else -> return null
        }
        val bits = resolve(use.ssaValue ?: return null) ?: return null
        return bits.takeIf { it.wide == wide }
    }

    private fun resolve(value: SsaValue): Bits? {
        if (cache.containsKey(value)) return cache[value]
        val active = HashSet<SsaValue>()
        val stack = ArrayList<Frame>()
        fun enter(v: SsaValue) {
            val def = v.assign.parent
            val width = when (def?.result?.type) {
                IrType.NARROW -> false
                IrType.WIDE -> true
                else -> null
            }
            if (def?.opcode == IrOpcode.CONST && width != null && def.argCount == 1) {
                val lit = def.getArg(0) as? LiteralOperand
                cache[v] = lit?.let { Bits(it.value, width) }
                return
            }
            if (def == null || !(def is PhiInstruction || def.opcode == IrOpcode.MOVE && width != null && def.argCount == 1) || def.argCount == 0) {
                cache[v] = null
                return
            }
            val sources = def.args.map { (it as? RegisterOperand)?.ssaValue }
            if (sources.any { it == null }) {
                cache[v] = null
                return
            }
            active.add(v)
            stack.add(Frame(v, sources.filterNotNull()))
        }
        enter(value)
        while (stack.isNotEmpty()) {
            cancellation.ensureActive()
            val frame = stack.last()
            if (frame.next < frame.sources.size) {
                val source = frame.sources[frame.next++]
                if (source in active) cache[source] = null
                else if (!cache.containsKey(source)) enter(source)
                continue
            }
            if (!cache.containsKey(frame.value)) {
                val first = cache[frame.sources.first()]
                val def = frame.value.assign.parent!!
                val matchingWidth = def is PhiInstruction ||
                    (def.result?.type == IrType.WIDE) == first?.wide
                cache[frame.value] = first?.takeIf { matchingWidth && frame.sources.all { cache[it] == first } }
            }
            active.remove(frame.value)
            stack.removeAt(stack.lastIndex)
        }
        return cache[value]
    }
}
