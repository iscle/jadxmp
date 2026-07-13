package com.jadxmp.ir.node

/**
 * A node that can appear as a child in the region tree: either a [BasicBlock] (a leaf of straight-line
 * instructions) or a `Region` (a nested control-flow construct).
 *
 * jadx: IContainer (IBlock | IRegion)
 *
 * Not `sealed`: its two implementations live in different packages (`node` and `region`). Callers
 * branch with `when (c) { is BasicBlock -> …; is Region -> …; else -> … }`; `Region` itself is
 * sealed, so region kinds stay exhaustive.
 */
interface IrContainer
