package com.jadxmp.codegen

/**
 * Immutable offset→annotation index plus the source-line→bytecode map for one generated file.
 * **jadx: ICodeMetadata / CodeMetadataStorage**
 *
 * Backs the GUI: given a caret character offset it answers "what is here" ([at]), "what encloses this"
 * ([nodeAt]), and "find the nearest definition/reference in either direction" ([searchUp]/[searchDown]).
 *
 * jadx uses a `TreeMap` with a reversed comparator (a `NavigableMap`); the JDK collection has no
 * multiplatform equivalent, so we store annotations in an offset-sorted parallel array and binary
 * search it. The exposed lookups reproduce jadx's `NavigableMap` semantics exactly:
 *  - "up" means toward **smaller** offsets (earlier in the file),
 *  - "down" means toward **larger** offsets (later in the file).
 */
class CodeMetadata internal constructor(
    // Ascending, strictly increasing character offsets; parallel to [annotations].
    private val offsets: IntArray,
    private val annotations: Array<CodeAnnotation>,
    /** Source (1-based) line number → originating bytecode offset. jadx: line mapping. */
    val lineMapping: Map<Int, Int>,
) {
    val size: Int get() = offsets.size

    /** All annotations keyed by character offset, in ascending offset order. */
    fun asMap(): Map<Int, CodeAnnotation> {
        val map = LinkedHashMap<Int, CodeAnnotation>(offsets.size)
        for (i in offsets.indices) map[offsets[i]] = annotations[i]
        return map
    }

    /** The annotation at exactly [offset], or null. jadx: getAt. */
    fun at(offset: Int): CodeAnnotation? {
        val i = indexOfExact(offset)
        return if (i >= 0) annotations[i] else null
    }

    /**
     * The annotation at the greatest offset strictly **less** than [offset] (the closest one "up" in
     * the file), or null. jadx: getClosestUp.
     */
    fun closestUp(offset: Int): CodeAnnotation? {
        val i = floorIndex(offset - 1)
        return if (i >= 0) annotations[i] else null
    }

    /**
     * Visit annotations from [startOffset] toward **smaller** offsets (inclusive), newest position
     * first, returning the first non-null result of [visitor]. jadx: searchUp(startPos, visitor).
     */
    fun <T> searchUp(startOffset: Int, visitor: (offset: Int, annotation: CodeAnnotation) -> T?): T? {
        var i = floorIndex(startOffset)
        while (i >= 0) {
            visitor(offsets[i], annotations[i])?.let { return it }
            i--
        }
        return null
    }

    /**
     * Visit annotations from [startOffset] toward **larger** offsets (inclusive), returning the first
     * non-null result of [visitor]. jadx: searchDown(startPos, visitor).
     */
    fun <T> searchDown(startOffset: Int, visitor: (offset: Int, annotation: CodeAnnotation) -> T?): T? {
        var i = ceilIndex(startOffset)
        while (i in offsets.indices) {
            visitor(offsets[i], annotations[i])?.let { return it }
            i++
        }
        return null
    }

    /** First annotation of a matching category at or above (smaller offset than) [offset]. jadx: searchUp(pos, annType). */
    fun searchUp(offset: Int, predicate: (CodeAnnotation) -> Boolean): CodeAnnotation? =
        searchUp(offset) { _, ann -> if (predicate(ann)) ann else null }

    /** First annotation of a matching category at or below (larger offset than) [offset]. jadx: searchDown(pos, annType). */
    fun searchDown(offset: Int, predicate: (CodeAnnotation) -> Boolean): CodeAnnotation? =
        searchDown(offset) { _, ann -> if (predicate(ann)) ann else null }

    /**
     * The class or method whose body encloses [offset], or null if [offset] is outside every body.
     * jadx: getNodeAt.
     *
     * Walks upward (toward smaller offsets) tracking brace nesting: each [NodeEndAnnotation] means we
     * stepped out of a sibling body, so its matching declaration must be skipped; the first
     * class/method [DefinitionAnnotation] seen at nesting level 0 is the enclosing node.
     */
    fun nodeAt(offset: Int): CodeNodeRef? {
        var nesting = 0
        return searchUp(offset) { _, ann ->
            when (ann) {
                is NodeEndAnnotation -> {
                    nesting++
                    null
                }
                is DefinitionAnnotation -> {
                    val r = ann.ref
                    if (r.refKind == RefKind.CLASS || r.refKind == RefKind.METHOD) {
                        if (nesting == 0) r else { nesting--; null }
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
    }

    /**
     * The first class or method declared at or after (larger offset than) [offset]. jadx: getNodeBelow.
     */
    fun nodeBelow(offset: Int): CodeNodeRef? =
        searchDown(offset) { _, ann ->
            if (ann is DefinitionAnnotation && (ann.ref.refKind == RefKind.CLASS || ann.ref.refKind == RefKind.METHOD)) {
                ann.ref
            } else {
                null
            }
        }

    // ---- binary search helpers over the ascending [offsets] array ----

    private fun indexOfExact(offset: Int): Int {
        var lo = 0
        var hi = offsets.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = offsets[mid]
            when {
                v < offset -> lo = mid + 1
                v > offset -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Index of the greatest offset ≤ [offset], or -1. */
    private fun floorIndex(offset: Int): Int {
        var lo = 0
        var hi = offsets.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= offset) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    /** Index of the least offset ≥ [offset], or offsets.size (out of range). */
    private fun ceilIndex(offset: Int): Int {
        var lo = 0
        var hi = offsets.size - 1
        var result = offsets.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] >= offset) {
                result = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return result
    }

    companion object {
        val EMPTY: CodeMetadata = CodeMetadata(IntArray(0), emptyArray(), emptyMap())

        /**
         * Build from an offset→annotation map (last write per offset wins, as in jadx) and a line map.
         */
        fun build(annotations: Map<Int, CodeAnnotation>, lineMapping: Map<Int, Int>): CodeMetadata {
            if (annotations.isEmpty() && lineMapping.isEmpty()) return EMPTY
            val sortedOffsets = annotations.keys.toIntArray()
            sortedOffsets.sort()
            val anns = Array(sortedOffsets.size) { annotations.getValue(sortedOffsets[it]) }
            return CodeMetadata(sortedOffsets, anns, lineMapping)
        }
    }
}
