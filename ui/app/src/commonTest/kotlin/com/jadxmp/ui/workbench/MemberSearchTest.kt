package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.TreeNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for the pure member scan ([MemberSearch]) backing the Methods/Fields scopes — the kind
 * filter, name/signature matching, capping, fault isolation and cancellation rules, asserted directly
 * with a fake `membersOf`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberSearchTest {

    private fun classRef(name: String) = MemberSearch.ClassRef(NodeId("cls:$name"), name, "pkg")

    private fun field(owner: String, name: String, sig: String) =
        TreeNode(NodeId("mbr:$owner#$owner#F:$sig"), name, NodeKind.FIELD, hasChildren = false, secondary = sig)

    private fun method(owner: String, name: String, sig: String) =
        TreeNode(NodeId("mbr:$owner#$owner#M:$sig"), name, NodeKind.METHOD, hasChildren = false, secondary = sig)

    private fun nested(owner: String, name: String) =
        TreeNode(NodeId("mbr:$owner#$owner#N:$name"), name, NodeKind.CLASS, hasChildren = true, secondary = name)

    private class RecordingSink : MemberSearch.ScanSink {
        val matches = mutableListOf<MemberMatch>()
        val progress = mutableListOf<Pair<Int, Int>>()
        override fun onMatch(match: MemberMatch) { matches += match }
        override fun onProgress(scanned: Int, total: Int) { progress += scanned to total }
    }

    @Test
    fun methodsScopeMatchesMethodsByNameAndSignatureAndIgnoresFields() = runTest {
        val classes = listOf(classRef("A"))
        val members = mapOf(
            NodeId("cls:A") to listOf(
                field("A", "runningTotal", "runningTotal: int"), // FIELD — excluded from Methods
                method("A", "run", "run(): void"),
                method("A", "stop", "stop(): void"),
                nested("A", "Inner"), // NESTED_CLASS — excluded from Methods
            ),
        )
        val sink = RecordingSink()
        val summary = MemberSearch.scan(classes, setOf(NodeKind.METHOD), MemberSearch.Query("run"), sink) {
            members[it]
        }
        // "run" matches the method name of run(); it also appears in the FIELD's name/signature but the
        // kind filter excludes the field — proving the filter, not just the query.
        assertEquals(listOf("run"), sink.matches.map { it.displayName })
        assertEquals(1, summary.matches)
        assertEquals("A", sink.matches.single().ownerSimpleName)
    }

    @Test
    fun fieldsScopeMatchesOnlyFields() = runTest {
        val classes = listOf(classRef("A"))
        val members = mapOf(
            NodeId("cls:A") to listOf(
                field("A", "count", "count: int"),
                method("A", "count", "count(): int"), // same name, wrong kind for Fields
            ),
        )
        val sink = RecordingSink()
        MemberSearch.scan(classes, setOf(NodeKind.FIELD), MemberSearch.Query("count"), sink) { members[it] }
        assertEquals(1, sink.matches.size)
        assertEquals("count: int", sink.matches.single().signature, "the field matched, not the method")
    }

    @Test
    fun matchesAgainstSignatureNotJustName() = runTest {
        val classes = listOf(classRef("A"))
        val members = mapOf(NodeId("cls:A") to listOf(method("A", "run", "run(Bundle): void")))
        val sink = RecordingSink()
        // "Bundle" appears only in the signature.
        MemberSearch.scan(classes, setOf(NodeKind.METHOD), MemberSearch.Query("Bundle"), sink) { members[it] }
        assertEquals(1, sink.matches.size)
    }

    @Test
    fun streamsAcrossClasses() = runTest {
        val classes = listOf(classRef("A"), classRef("B"))
        val members = mapOf(
            NodeId("cls:A") to listOf(method("A", "go", "go(): void")),
            NodeId("cls:B") to listOf(method("B", "go", "go(): int")),
        )
        val sink = RecordingSink()
        val summary = MemberSearch.scan(classes, setOf(NodeKind.METHOD), MemberSearch.Query("go"), sink) { members[it] }
        assertEquals(2, summary.matches)
        assertEquals(listOf("A", "B"), sink.matches.map { it.ownerSimpleName })
    }

    @Test
    fun capsResultsAndFlagsTruncation() = runTest {
        val classes = (1..10).map { classRef("C$it") }
        val members = { id: NodeId -> (1..5).map { method(id.value, "hit$it", "hit$it(): void") } }
        val sink = RecordingSink()
        val summary = MemberSearch.scan(classes, setOf(NodeKind.METHOD), MemberSearch.Query("hit"), sink, maxResults = 7) {
            members(it)
        }
        assertEquals(7, sink.matches.size, "must not emit past the cap")
        assertTrue(summary.truncated, "hitting the cap must be reported")
        assertTrue(summary.scanned < 10, "scan stops early once capped")
    }

    @Test
    fun skipsClassesWhoseMembersAreUnavailableAndKeepsGoing() = runTest {
        val classes = listOf(classRef("A"), classRef("Bad"), classRef("Throws"), classRef("C"))
        val members = mapOf(
            NodeId("cls:A") to listOf(method("A", "hit", "hit(): void")),
            NodeId("cls:C") to listOf(method("C", "hit", "hit(): void")),
        )
        val sink = RecordingSink()
        val summary = MemberSearch.scan(classes, setOf(NodeKind.METHOD), MemberSearch.Query("hit"), sink) { id ->
            when (id.value) {
                "cls:Bad" -> null
                "cls:Throws" -> throw IllegalStateException("boom")
                else -> members[id]
            }
        }
        assertEquals(4, summary.scanned)
        assertEquals(2, summary.failed, "the null and the throwing class both count as failed")
        assertEquals(2, summary.matches)
    }

    @Test
    fun propagatesCancellationFromMembersOfWithoutSwallowingIt() = runTest {
        val sink = RecordingSink()
        assertFailsWith<CancellationException> {
            MemberSearch.scan(listOf(classRef("A"), classRef("B")), setOf(NodeKind.METHOD), MemberSearch.Query("x"), sink) {
                throw CancellationException("job cancelled mid-enumeration")
            }
        }
        assertEquals(listOf(0 to 2), sink.progress, "only the initial progress ran; the scan aborted at once")
        assertTrue(sink.matches.isEmpty())
    }

    @Test
    fun isCooperativelyCancelable() = runTest {
        val classes = (1..200).map { classRef("C$it") }
        val sink = RecordingSink()
        val job = launch {
            MemberSearch.scan(classes, setOf(NodeKind.METHOD), MemberSearch.Query("x"), sink) { delay(10); emptyList() }
        }
        advanceTimeBy(35)
        job.cancel()
        advanceUntilIdle()
        val furthest = sink.progress.maxOf { it.first }
        assertTrue(furthest in 1 until 200, "cancel stops the scan mid-way (reached $furthest of 200)")
    }

    @Test
    fun blankQueryMatchesNothing() = runTest {
        val sink = RecordingSink()
        val summary = MemberSearch.scan(listOf(classRef("A")), setOf(NodeKind.METHOD), MemberSearch.Query("  "), sink) {
            listOf(method("A", "run", "run(): void"))
        }
        assertEquals(0, summary.matches)
        assertEquals(0, summary.scanned)
    }
}
