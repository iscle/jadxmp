package com.jadxmp.ui.client

import com.jadxmp.api.ClassInfo
import com.jadxmp.api.ClassKind
import com.jadxmp.api.Modifier

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

/**
 * Map a source [Modifier] set to the tree-node [Visibility] used for the badge overlay. Total: an
 * explicit `public`/`protected`/`private` wins; with none present the declaration is
 * [Visibility.PACKAGE_PRIVATE] (the JVM default). A `null` set (unknown class/member) yields `null` —
 * no overlay. Pure, so it is unit-tested without a `core:api` `Decompiler`.
 */
internal fun visibilityOf(modifiers: Set<Modifier>?): Visibility? = when {
    modifiers == null -> null
    Modifier.PRIVATE in modifiers -> Visibility.PRIVATE
    Modifier.PROTECTED in modifiers -> Visibility.PROTECTED
    Modifier.PUBLIC in modifiers -> Visibility.PUBLIC
    else -> Visibility.PACKAGE_PRIVATE
}

/** The tree badge for a class row: its kind glyph + its access-visibility overlay, from one [ClassInfo]. */
internal data class ClassNodeBadge(val kind: NodeKind, val visibility: Visibility?)

/** Derive a class row's [ClassNodeBadge] (kind + visibility) from one cheap no-decompile [ClassInfo]. */
internal fun classNodeBadge(info: ClassInfo?): ClassNodeBadge =
    ClassNodeBadge(classNodeKind(info), visibilityOf(info?.modifiers))
