package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind

/** How a breadcrumb segment should read — drives its color in the breadcrumb bar. */
enum class CrumbEmphasis { PLAIN, TYPE, MEMBER }

/** One `package › Class › member()` breadcrumb segment. */
data class BreadcrumbSegment(val text: String, val emphasis: CrumbEmphasis)

/**
 * Derive the editor breadcrumb (package › Class › member) from a node's stable id, matching the
 * mockup's breadcrumb bar. Pure so it is unit-testable without any client/composition.
 *
 * The id scheme mirrors [com.jadxmp.ui.client.StubData] (and the intended `core:api` shape):
 *  - `cls:<fqcn>`                → package, type
 *  - `mbr:<fqcn>#<member>`       → package, type, member()
 *  - `res:<path/with/slashes>`   → path segments
 *  - `pkg:<name>`                → the package
 *
 * The member id carries no descriptor, so a FIELD and a METHOD id are shape-identical
 * (`mbr:Foo#count` vs `mbr:Foo#run`). [kind] disambiguates them: only a METHOD gets the trailing
 * `()`. When [kind] is unknown (null — e.g. a jump-to-definition target) we keep the historic
 * heuristic (append `()` unless it is a `<init>` constructor or already ends in `)`).
 */
fun breadcrumbSegments(nodeId: NodeId, kind: NodeKind? = null): List<BreadcrumbSegment> {
    val v = nodeId.value
    val prefix = v.substringBefore(':', missingDelimiterValue = "")
    val rest = if (prefix.isEmpty()) v else v.substringAfter(':')
    return when (prefix) {
        "cls" -> classCrumbs(rest, member = null, kind = kind)
        "mbr" -> {
            val fqcn = rest.substringBefore('#')
            val member = rest.substringAfter('#', missingDelimiterValue = "")
            classCrumbs(fqcn, member.ifEmpty { null }, kind)
        }
        "res" -> rest.split('/').filter { it.isNotEmpty() }
            .map { BreadcrumbSegment(it, CrumbEmphasis.PLAIN) }
        "pkg" -> listOf(BreadcrumbSegment(rest, CrumbEmphasis.PLAIN))
        else -> listOf(BreadcrumbSegment(rest.ifEmpty { v }, CrumbEmphasis.PLAIN))
    }
}

private fun classCrumbs(fqcn: String, member: String?, kind: NodeKind?): List<BreadcrumbSegment> {
    val type = fqcn.substringAfterLast('.')
    val pkg = fqcn.substringBeforeLast('.', missingDelimiterValue = "")
    val out = ArrayList<BreadcrumbSegment>(3)
    if (pkg.isNotEmpty()) out.add(BreadcrumbSegment(pkg, CrumbEmphasis.PLAIN))
    if (type.isNotEmpty()) out.add(BreadcrumbSegment(type, CrumbEmphasis.TYPE))
    if (member != null) {
        out.add(BreadcrumbSegment(memberLabel(member, kind), CrumbEmphasis.MEMBER))
    }
    return out
}

/** A member's breadcrumb label: methods read `name()`, fields read `name` (no spurious parens). */
private fun memberLabel(member: String, kind: NodeKind?): String = when {
    member == "<init>" || member.endsWith(")") -> member
    kind == NodeKind.FIELD -> member
    // METHOD, or unknown kind (null) → keep the historic method-looking rendering.
    else -> "$member()"
}
