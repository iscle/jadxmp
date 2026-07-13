package com.jadxmp.ui.client

import androidx.compose.runtime.Immutable
import com.jadxmp.codegen.CodeMetadata
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.DefinitionAnnotation

/**
 * What sort of declared member a tree/search row represents. A UI-local projection of the engine's
 * `MemberKind` (kept engine-free so the pure [MemberTree]/[MemberSearch] seams are testable in
 * commonTest without a `core:api` `Decompiler`). The production client maps `MemberInfo.kind` to this.
 */
enum class MemberSort { FIELD, METHOD, CONSTRUCTOR, STATIC_INITIALIZER, NESTED_CLASS }

/**
 * One declared member of a class, projected for the tree/search from the engine's `MemberInfo`. Only
 * what the UI needs to render + recurse: the display label, a one-line signature, and — for a nested
 * class — its binary [nestedFqn] so expanding the row can enumerate *its* members in turn.
 *
 * The engine's `CodeNodeRef` navigation key is deliberately NOT carried here: it lives only in the
 * production client, which re-derives it (from the same enumeration) when resolving a navigation,
 * matching by the stable [MemberTree.slug]. That keeps this model — and the seams over it — free of
 * codegen types the tests would otherwise need.
 */
@Immutable
data class MemberDescriptor(
    val sort: MemberSort,
    val displayName: String,
    val signature: String,
    /** For a [MemberSort.NESTED_CLASS], the nested class's binary fqn (children recurse on it); else null. */
    val nestedFqn: String? = null,
    /**
     * For a [MemberSort.NESTED_CLASS], the nested class's own kind (interface/enum/annotation/class) so
     * its row badges like a top-level class row; null (→ generic class badge) for other sorts or when
     * the kind is unknown.
     */
    val nestedKind: NodeKind? = null,
)

/**
 * Where a member's definition lives: the top-level class tab to open ([classNodeId], always a `cls:`
 * node) and the 1-based source [line] to scroll to — `null` when the member has no resolvable
 * definition offset yet (a static initializer, or a key the current backend does not annotate). A null
 * line means "open the class, don't scroll" — honest, never an error.
 */
@Immutable
data class MemberLocation(val classNodeId: NodeId, val line: Int?)

/**
 * Pure builder of member tree rows and the owner of the **member NodeId scheme**. Compose-free and
 * engine-free, so the id round-trip and the row shapes are unit-tested directly.
 *
 * ## NodeId scheme — `mbr:<topLevelFqn>#<ownerFqn>#<slug>`
 *  - **topLevelFqn** — the top-level class that is decompiled/opened for navigation (a nested member's
 *    definition annotation lives in its *top-level* ancestor's metadata, which is the single emitted
 *    unit). Carried through every level so nested recursion never has to recompute it.
 *  - **ownerFqn** — the *immediate* declaring class (== topLevel for a top-level member; the nested
 *    class itself for a member of a nested class). Used to re-enumerate members and to rebuild the
 *    navigation key.
 *  - **slug** — a stable identity unique **within the owner** (sort + signature), disambiguating
 *    overloads. Binary fqns never contain `#`, and a signature never contains `#`, so the three fields
 *    split cleanly on the first two `#`.
 *
 * The scheme does not collide with `cls:`/`pkg:`/`res:` (distinct prefix).
 */
object MemberTree {
    const val PREFIX: String = "mbr:"
    private const val SEP: Char = '#'

    /** Stable identity of a member within its owner class (disambiguates overloads via the signature). */
    fun slug(sort: MemberSort, signature: String): String = "${sortCode(sort)}:$signature"

    fun encodeId(topLevelFqn: String, ownerFqn: String, member: MemberDescriptor): String =
        "$PREFIX$topLevelFqn$SEP$ownerFqn$SEP${slug(member.sort, member.signature)}"

    /** (topLevel, owner, slug) parsed from a member id, or null when [id] is not a well-formed member id. */
    fun parse(id: NodeId): Triple<String, String, String>? {
        val v = id.value
        if (!v.startsWith(PREFIX)) return null
        val rest = v.substring(PREFIX.length)
        val first = rest.indexOf(SEP)
        if (first < 0) return null
        val second = rest.indexOf(SEP, first + 1)
        if (second < 0) return null
        return Triple(rest.substring(0, first), rest.substring(first + 1, second), rest.substring(second + 1))
    }

    /** Build the ordered member rows for [ownerFqn] (whose top-level ancestor is [topLevelFqn]). */
    fun memberNodes(topLevelFqn: String, ownerFqn: String, members: List<MemberDescriptor>): List<TreeNode> =
        members.map { m ->
            TreeNode(
                id = NodeId(encodeId(topLevelFqn, ownerFqn, m)),
                label = m.displayName,
                kind = nodeKind(m),
                // Only nested classes recurse into their own members; methods/fields are leaves.
                hasChildren = m.sort == MemberSort.NESTED_CLASS,
                secondary = m.signature,
            )
        }

    private fun nodeKind(m: MemberDescriptor): NodeKind = when (m.sort) {
        MemberSort.FIELD -> NodeKind.FIELD
        // A nested class badges by its own kind (interface/enum/annotation/class); default generic class.
        MemberSort.NESTED_CLASS -> m.nestedKind ?: NodeKind.CLASS
        // Methods, constructors and the static initializer all read as a method row (icon + semantics).
        MemberSort.METHOD, MemberSort.CONSTRUCTOR, MemberSort.STATIC_INITIALIZER -> NodeKind.METHOD
    }

    private fun sortCode(sort: MemberSort): Char = when (sort) {
        MemberSort.FIELD -> 'F'
        MemberSort.METHOD -> 'M'
        MemberSort.CONSTRUCTOR -> 'C'
        MemberSort.STATIC_INITIALIZER -> 'S'
        MemberSort.NESTED_CLASS -> 'N'
    }
}

/**
 * Resolves a member's `CodeNodeRef` key to a 1-based source line inside a decompiled class — the pure
 * heart of member navigation, kept free of the client so it is unit-testable with a hand-built
 * [CodeMetadata]. jadx: the GUI's `getDefPos`/offset→line walk.
 */
internal object MemberDefinitionLocator {

    /**
     * The character offset of the [DefinitionAnnotation] whose ref equals [key], or null if none. The
     * metadata map is ascending by offset, so the first match is the earliest (a definition ref is
     * emitted once at its declaration site).
     */
    fun definitionOffset(metadata: CodeMetadata, key: CodeNodeRef): Int? {
        for ((offset, ann) in metadata.asMap()) {
            if (ann is DefinitionAnnotation && ann.ref == key) return offset
        }
        return null
    }

    /** 1-based line containing character [offset] of [source] (count of newlines before it, plus one). */
    fun offsetToLine(source: String, offset: Int): Int {
        if (offset <= 0) return 1
        val end = offset.coerceAtMost(source.length)
        var line = 1
        for (i in 0 until end) if (source[i] == '\n') line++
        return line
    }

    /** The 1-based line of [key]'s definition in [source]/[metadata], or null when it can't be resolved. */
    fun locate(source: String, metadata: CodeMetadata?, key: CodeNodeRef): Int? {
        val meta = metadata ?: return null
        val offset = definitionOffset(meta, key) ?: return null
        return offsetToLine(source, offset)
    }
}
