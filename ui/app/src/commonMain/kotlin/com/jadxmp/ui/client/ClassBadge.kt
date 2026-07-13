package com.jadxmp.ui.client

import com.jadxmp.api.ClassInfo
import com.jadxmp.api.ClassKind

/**
 * Map an engine [ClassInfo] to the tree [NodeKind] that drives a class row's badge/icon — so an
 * interface, enum, annotation and plain class each render a distinct glyph (via [com.jadxmp.ui.component.NodeKindBadge])
 * instead of all reading as a generic class.
 *
 * Pure and total: a `null` info — an unknown class, nothing loaded, or a fault in the cheap
 * no-decompile lookup — falls back to the generic [NodeKind.CLASS] (rule 4: never a crash, just a
 * less-specific badge). Kept a free function so the mapping is unit-tested without a `core:api`
 * `Decompiler`.
 */
internal fun classNodeKind(info: ClassInfo?): NodeKind = when (info?.kind) {
    ClassKind.INTERFACE -> NodeKind.INTERFACE
    ClassKind.ENUM -> NodeKind.ENUM
    ClassKind.ANNOTATION -> NodeKind.ANNOTATION_CLASS
    ClassKind.CLASS, null -> NodeKind.CLASS
}
