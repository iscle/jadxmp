package com.jadxmp.api

import com.jadxmp.codegen.CodeMetadata
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.RefKind
import com.jadxmp.codegen.ReferenceAnnotation

/**
 * One site where a queried symbol is **used** (referenced), the unit [Decompiler.findUsages] returns.
 *
 * Find-usages is the *inverse* of the click-to-definition metadata the codegen backend already emits: a
 * [ReferenceAnnotation] records, at a character [offset] in a class's source, which class/method/field is
 * referenced there. Inverting (referring-site → target) into (target → [referring sites]) across every
 * class yields exactly the list jadx-gui's "Find Usages" shows.
 *
 * @property fromClass the **binary** fully-qualified name of the top-level class (the output *file*) whose
 *   decompiled source contains this use — the SAME key space as [Decompiler.classNames] /
 *   [Decompiler.decompileClass], so a UI navigates to the site by opening `fromClass` and scrolling to
 *   [offset]. When the use is inside a nested class, this is still the top-level ancestor (one file per
 *   output unit); the precise declaring class is available on [fromMember]'s owner.
 * @property fromMember the enclosing member when the use sits inside a method/constructor/static-init body
 *   — a [MethodNodeRef] whose `ownerClass` is the (possibly nested) declaring class — or `null` when the
 *   use is at class scope (an `extends`/`implements` clause, a field initializer/type). It is the same ref
 *   scheme as [MemberInfo.key], so a caller can match it back to a tree row.
 * @property offset 0-based character offset of the referencing token into `fromClass`'s decompiled
 *   [DecompiledClass.code] — the exact caret/jump target for the code viewer.
 * @property line 1-based source line of [offset] within that code, for a human-readable `File:line` label.
 * @property kind whether the referenced target is a class, method or field. Always equals the queried
 *   target's [CodeNodeRef.refKind] (a single query is homogeneous); carried so a UI holding sites from
 *   several queries can badge each row without re-deriving it.
 */
public data class UsageSite(
    val fromClass: String,
    val fromMember: CodeNodeRef?,
    val offset: Int,
    val line: Int,
    val kind: RefKind,
)

/**
 * The immutable inverse index: target symbol → its deterministically-ordered [UsageSite]s. Built once per
 * output format (see [Decompiler.findUsages]) by [buildUsageIndex] and cached; a query is a single map
 * lookup. Keyed by [CodeNodeRef] — the exact identity the codegen references, [MemberInfo.key], and a
 * `ClassNodeRef(binaryName)` all share — so no parallel symbol scheme is introduced.
 *
 * Rule 4 (fault isolation): an unknown target is not a member of the map and yields an empty list, never a
 * throw — the query is total.
 */
internal class UsageIndex(private val byTarget: Map<CodeNodeRef, List<UsageSite>>) {

    /** Every recorded use of [target], already sorted; empty for a symbol nothing references. */
    fun query(target: CodeNodeRef): List<UsageSite> = byTarget[target] ?: emptyList()

    companion object {
        val EMPTY: UsageIndex = UsageIndex(emptyMap())
    }
}

/**
 * The decompiled source of one class as an inversion input: its **binary** [binaryName] (the reported
 * [UsageSite.fromClass]), the emitted [code] (for offset→line), and its [metadata] (the reference
 * annotations to invert). Deliberately decoupled from [DecompiledClass]/[Decompiler] so the pure
 * inversion in [buildUsageIndex] is unit-testable with synthetic metadata and carries no engine state.
 */
internal class ClassUsageSource(
    val binaryName: String,
    val code: String,
    val metadata: CodeMetadata,
)

/**
 * Invert the click-to-definition metadata of every [entry] into a target→uses index.
 *
 * For each class we walk its [CodeMetadata] annotations, keep only [ReferenceAnnotation]s (a *use* of a
 * class/method/field — declarations and local-variable occurrences are deliberately excluded, so a
 * symbol's own definition site never counts as a usage, matching jadx-gui), and bucket each by its target
 * ref. The enclosing member is recovered with [CodeMetadata.nodeAt] (the same walk-up the code viewer uses
 * for "what encloses the caret"): a method/ctor/`<clinit>` becomes [UsageSite.fromMember]; a class-scope
 * use leaves it `null`.
 *
 * **Determinism (rule 2 for a query feature):** every target's site list is sorted by `(fromClass, offset)`
 * so the output is stable regardless of class iteration order or map hashing — tests and the UI see one
 * fixed order. Only [RefKind.CLASS]/[RefKind.METHOD]/[RefKind.FIELD] targets are indexed; a stray
 * variable/package ref (which [ReferenceAnnotation] never actually carries) is ignored defensively.
 */
internal fun buildUsageIndex(entries: List<ClassUsageSource>): UsageIndex {
    if (entries.isEmpty()) return UsageIndex.EMPTY
    val byTarget = LinkedHashMap<CodeNodeRef, MutableList<UsageSite>>()
    for (entry in entries) {
        val newlines = newlineOffsets(entry.code)
        val meta = entry.metadata
        // asMap() iterates in ascending offset order; each entry is one annotation at a character offset.
        for ((offset, annotation) in meta.asMap()) {
            if (annotation !is ReferenceAnnotation) continue
            val target = annotation.ref
            if (target.refKind !in INDEXED_KINDS) continue
            val enclosing = meta.nodeAt(offset)
            val site = UsageSite(
                fromClass = entry.binaryName,
                // nodeAt yields the innermost enclosing CLASS or METHOD; only a method body gives an
                // enclosing member, so a class-scope use (extends/implements, field-type) reports null.
                fromMember = enclosing?.takeIf { it.refKind == RefKind.METHOD },
                offset = offset,
                line = lineOf(newlines, offset),
                kind = target.refKind,
            )
            byTarget.getOrPut(target) { ArrayList() }.add(site)
        }
    }
    for (sites in byTarget.values) sites.sortWith(USAGE_SITE_ORDER)
    return UsageIndex(byTarget)
}

/** Only genuine cross-symbol references are usages; local variables carry their own annotation type. */
private val INDEXED_KINDS = setOf(RefKind.CLASS, RefKind.METHOD, RefKind.FIELD)

/** Stable order: group a target's uses by referring file, then by position within it. */
private val USAGE_SITE_ORDER: Comparator<UsageSite> =
    compareBy({ it.fromClass }, { it.offset })

/**
 * Ascending character offsets of every `'\n'` in [code]. Precomputed once per class so [lineOf] is a
 * binary search rather than an O(offset) rescan per site — a class with many references stays linear.
 */
private fun newlineOffsets(code: String): IntArray {
    var count = 0
    for (c in code) if (c == '\n') count++
    val result = IntArray(count)
    var i = 0
    var idx = 0
    while (i < code.length) {
        if (code[i] == '\n') {
            result[idx] = i
            idx++
        }
        i++
    }
    return result
}

/**
 * The 1-based line of character [offset] given the sorted [newlines]: 1 + the count of newline offsets
 * strictly less than [offset]. A pure, deterministic function of the emitted text.
 */
private fun lineOf(newlines: IntArray, offset: Int): Int {
    // Count newlines with index < offset via binary search for the first newline offset >= offset.
    var lo = 0
    var hi = newlines.size // exclusive
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (newlines[mid] < offset) lo = mid + 1 else hi = mid
    }
    return lo + 1
}
