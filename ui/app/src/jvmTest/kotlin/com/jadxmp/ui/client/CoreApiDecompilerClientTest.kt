package com.jadxmp.ui.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [CoreApiDecompilerClient] end-to-end with the real `hello.dex` bytes, proving the whole seam:
 * engine load → class tree, on-demand decompile → a metadata-enriched [CodeDocument]. This is the
 * production path the desktop shell runs, exercised without any Compose/UI.
 *
 * A jvmTest (not commonTest) purely to read the `.dex` fixture from the JVM classpath and to use
 * `runBlocking`; the client it drives is all `commonMain` and wasm-safe.
 */
class CoreApiDecompilerClientTest {

    private fun helloDexBytes(): ByteArray =
        javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: error("hello.dex test resource not found")

    private fun openedClient(): CoreApiDecompilerClient = runBlocking {
        CoreApiDecompilerClient().also { it.open(OpenRequest("hello.dex", helloDexBytes())) }
    }

    @Test
    fun opensAndReportsReadySession() = runBlocking {
        val client = openedClient()
        val session = client.session.value
        assertTrue(session is SessionState.Ready, "expected Ready, got $session")
        assertTrue(session.classCount >= 1)
    }

    @Test
    fun treeContainsHelloWorld() = runBlocking {
        val client = openedClient()
        val roots = client.rootNodes(TreeKind.CLASSES)
        // HelloWorld is in the default package, so it appears as a root class node.
        val helloWorld = roots.firstOrNull { it.label == "HelloWorld" }
        assertTrue(
            helloWorld != null,
            "tree roots should contain HelloWorld; got ${roots.map { it.label }}",
        )
        assertEquals(NodeKind.CLASS, helloWorld.kind)
        assertEquals(NodeId("cls:HelloWorld"), helloWorld.id)
    }

    @Test
    fun decompilesHelloWorldToAHighlightedNavigableDocument() = runBlocking {
        val client = openedClient()
        val doc = client.code(NodeId("cls:HelloWorld"), CodeView.JAVA)
        val tokens = doc.lines.flatMap { it.tokens }
        assertTrue(tokens.isNotEmpty(), "document should have tokens")

        // 1) The greeting string literal survives to a STRING-colored token.
        val greeting = tokens.firstOrNull { it.kind == TokenKind.STRING && "Hello, World!" in it.text }
        assertTrue(
            greeting != null,
            "expected a STRING token with the greeting; strings=${tokens.filter { it.kind == TokenKind.STRING }.map { it.text }}",
        )

        // 2) The lexer colors Java keywords (e.g. class/public/static/void).
        val keywords = tokens.filter { it.kind == TokenKind.KEYWORD }.map { it.text }.toSet()
        assertTrue(keywords.contains("class"), "expected keyword coloring; keywords=$keywords")

        // 3) Engine CodeMetadata makes the class definition navigable: some token jumps to cls:HelloWorld.
        val navToSelf = tokens.filter { it.definition == NodeId("cls:HelloWorld") }
        assertTrue(
            navToSelf.isNotEmpty(),
            "expected a metadata-driven navigable token targeting the class definition",
        )
        // The class name itself is one of them, colored as a TYPE via metadata (not the lexer heuristic).
        assertTrue(
            navToSelf.any { it.text == "HelloWorld" && it.kind == TokenKind.TYPE },
            "the HelloWorld class-name token should be a navigable TYPE; got $navToSelf",
        )
    }

    @Test
    fun offersJavaAndKotlinAndRendersBothFromTheOneLoad() = runBlocking {
        val client = openedClient()
        val node = NodeId("cls:HelloWorld")

        // A class node offers the Java|Kotlin|Smali toggle, Java first (the default view).
        assertEquals(listOf(CodeView.JAVA, CodeView.KOTLIN, CodeView.SMALI), client.availableViews(node))

        val java = client.code(node, CodeView.JAVA).plainText()
        val kotlin = client.code(node, CodeView.KOTLIN).plainText()

        // Java render: Java `main` signature, `;` line endings.
        assertTrue(java.contains("static void main("), "expected the Java main signature:\n$java")
        assertTrue(java.lines().any { it.trimEnd().endsWith(";") }, "Java render should have `;` lines:\n$java")

        // Kotlin render: `fun`, no Java `new`, no `;` line endings.
        assertTrue(kotlin.contains("fun "), "expected a Kotlin `fun`:\n$kotlin")
        assertTrue(!kotlin.contains("new "), "Kotlin render must not emit Java `new`:\n$kotlin")
        assertTrue(kotlin.lines().none { it.trimEnd().endsWith(";") }, "Kotlin render must not end lines with `;`:\n$kotlin")

        // The two documents genuinely differ, and re-requesting each returns the SAME (cached, not
        // clobbered) content — the (node, view) cache keeps both formats simultaneously.
        assertTrue(java != kotlin, "Java and Kotlin renders must differ")
        assertEquals(java, client.code(node, CodeView.JAVA).plainText(), "Java render must survive a Kotlin render")
        assertEquals(kotlin, client.code(node, CodeView.KOTLIN).plainText(), "Kotlin render stays cached too")
    }

    @Test
    fun javaRenderIsUnaffectedByRenderingKotlinFirst() = runBlocking {
        val node = NodeId("cls:HelloWorld")
        // Java-only baseline: a fresh client whose engine never rendered Kotlin.
        val javaOnly = CoreApiDecompilerClient().let {
            it.open(OpenRequest("hello.dex", helloDexBytes()))
            it.code(node, CodeView.JAVA).plainText()
        }
        // Same client (same underlying Decompiler instance): render KOTLIN first, THEN Java. The shared
        // error attrs the Kotlin backend mutates must not leak a spurious marker into the Java render.
        val client = openedClient()
        client.code(node, CodeView.KOTLIN)
        val javaAfterKotlin = client.code(node, CodeView.JAVA).plainText()
        assertEquals(javaOnly, javaAfterKotlin, "Java render must be independent of a prior Kotlin render")
        assertEquals(
            javaOnly.contains("JADXMP ERROR"),
            javaAfterKotlin.contains("JADXMP ERROR"),
            "no spurious `// JADXMP ERROR` may appear only because Kotlin rendered first:\n$javaAfterKotlin",
        )
    }

    @Test
    fun decompiledDocumentHasMethodColoredTokens() = runBlocking {
        val client = openedClient()
        val tokens = client.code(NodeId("cls:HelloWorld"), CodeView.JAVA).lines.flatMap { it.tokens }
        // main/println render as method-kind tokens (from engine metadata or the name heuristic).
        assertTrue(
            tokens.any { it.kind == TokenKind.METHOD },
            "expected at least one METHOD-colored token in HelloWorld",
        )
    }

    @Test
    fun classExpandsToMembersAndAMemberNavigatesToItsDefinitionLine() = runBlocking {
        val client = openedClient()
        // A class row now expands to its declared members (no decompilation to enumerate them).
        val members = client.childNodes(NodeId("cls:HelloWorld"))
        assertTrue(members.isNotEmpty(), "HelloWorld should expose members; got $members")
        assertTrue(members.all { it.id.value.startsWith("mbr:") }, "member ids use the mbr: scheme")
        val main = members.firstOrNull { it.label == "main" }
        assertTrue(main != null, "HelloWorld.main should be a member row; got ${members.map { it.label }}")

        // Navigating the member resolves (via the real backend's DefinitionAnnotation) to its class tab
        // and a concrete source line — proving MemberInfo.key aligns with the emitted metadata.
        val location = client.memberLocation(main.id)
        assertTrue(location != null, "main must resolve to a location")
        assertEquals(NodeId("cls:HelloWorld"), location.classNodeId)
        assertTrue(location.line != null && location.line >= 1, "main's definition line must resolve; got ${location.line}")

        // The resolved line actually contains the method in the rendered document.
        val doc = client.code(NodeId("cls:HelloWorld"), CodeView.JAVA)
        val text = doc.lines.firstOrNull { it.number == location.line }?.tokens?.joinToString("") { it.text }
        assertTrue(text != null && "main" in text, "line ${location.line} should declare main; was: $text")
    }

    @Test
    fun rendersSmaliViewFromInputModelAndCachesIt() = runBlocking {
        val client = openedClient()
        val node = NodeId("cls:HelloWorld")
        val doc = client.code(node, CodeView.SMALI)
        assertEquals(CodeView.SMALI, doc.view)
        val text = doc.plainText()

        // Real, baksmali-faithful smali: class header, a method block, the main signature, a real
        // instruction, and a field reference in L…;->name:Type form.
        assertTrue(text.contains(".class LHelloWorld;"), "smali should have the class header:\n$text")
        assertTrue(text.contains(".method public static main([Ljava/lang/String;)V"), "smali main signature:\n$text")
        assertTrue(text.contains(".registers"), "smali should declare registers:\n$text")
        assertTrue(text.contains("invoke-"), "smali should contain a real invoke instruction:\n$text")
        assertTrue(text.contains("return-void"), "smali should contain return-void:\n$text")
        assertTrue(
            text.contains("Ljava/lang/System;->out:Ljava/io/PrintStream;"),
            "smali should show a field ref in L…;->name:Type form:\n$text",
        )

        // Directives are colored as keywords by the minimal smali colorizer.
        val keywords = doc.lines.flatMap { it.tokens }.filter { it.kind == TokenKind.KEYWORD }.map { it.text }
        assertTrue(keywords.any { it == ".class" || it == ".method" }, "smali directives should be KEYWORD; got $keywords")

        // SMALI caches alongside JAVA/KOTLIN under the (node, view) key — re-request returns identical text.
        assertEquals(text, client.code(node, CodeView.SMALI).plainText(), "smali render must stay cached")
    }

    @Test
    fun smaliForAMemberNodeRoutesToItsDeclaringClass() = runBlocking {
        // A member node's SMALI view must resolve to the member's own declaring dex class (smali is one
        // unit per dex class), not an error. For a top-level member the declaring class IS the class node,
        // so the member's smali equals the class's smali — exercising the mbr: → owner routing path.
        // (The nested-class → inner-fqn branch is logic-covered by smaliClassFqnOf but needs a nested-class
        // dex fixture to assert end-to-end; hello.dex has none.)
        val client = openedClient()
        val members = client.childNodes(NodeId("cls:HelloWorld"))
        val main = members.first { it.label == "main" }
        val memberSmali = client.code(main.id, CodeView.SMALI).plainText()
        val classSmali = client.code(NodeId("cls:HelloWorld"), CodeView.SMALI).plainText()
        assertEquals(classSmali, memberSmali, "a top-level member's smali is its declaring class's smali")
        assertTrue(memberSmali.contains(".class LHelloWorld;"), memberSmali)
    }

    @Test
    fun searchFindsClassByName() = runBlocking {
        val client = openedClient()
        val results = client.search(SearchQuery(text = "Hello"))
        assertTrue(
            results.matches.any { it.nodeId == NodeId("cls:HelloWorld") },
            "class search should find HelloWorld; got ${results.matches.map { it.title }}",
        )
    }

    @Test
    fun regexSearchMatchesAndInvalidRegexIsSafe() = runBlocking {
        val client = openedClient()
        val hit = client.search(SearchQuery(text = "Hello.*", useRegex = true))
        assertTrue(hit.matches.any { it.nodeId == NodeId("cls:HelloWorld") }, "regex should match HelloWorld")
        // An invalid pattern must not throw — it yields no matches.
        val bad = client.search(SearchQuery(text = "(unclosed", useRegex = true))
        assertTrue(bad.matches.isEmpty(), "invalid regex should be swallowed, not crash")
    }

    @Test
    fun codeOnNonClassNodeReturnsErrorDocumentNotThrow() = runBlocking {
        val client = openedClient()
        val pkgDoc = client.code(NodeId("pkg:whatever"), CodeView.JAVA)
        assertEquals(1, pkgDoc.lineCount)
        assertEquals(TokenKind.COMMENT, pkgDoc.lines.single().tokens.single().kind)

        val bogus = client.code(NodeId("cls:does.not.Exist"), CodeView.JAVA)
        assertTrue(bogus.plainText().contains("not found"), "missing class → error document, not an exception")
    }

    @Test
    fun garbageAndEmptyBytesFailGracefully() = runBlocking {
        val client = CoreApiDecompilerClient()

        client.open(OpenRequest("garbage.dex", ByteArray(64) { 0x7F }))
        val afterGarbage = client.session.value
        assertTrue(
            afterGarbage is SessionState.Failed ||
                (afterGarbage is SessionState.Ready && afterGarbage.classCount == 0),
            "garbage bytes should degrade, not crash; got $afterGarbage",
        )
        assertTrue(client.rootNodes(TreeKind.CLASSES).isEmpty(), "no tree from garbage input")

        // Empty input must also be safe.
        client.open(OpenRequest("empty.dex", ByteArray(0)))
        assertTrue(client.rootNodes(TreeKind.CLASSES).isEmpty())
    }

    @Test
    fun concurrentOpenAndDecompileNeverCorrupt() = runBlocking(Dispatchers.Default) {
        // Hammer the single lock: reopen the project while many decompiles are in flight on multiple
        // threads. The MUST-FIX guarantees documentCache/model are never mutated concurrently.
        val client = CoreApiDecompilerClient()
        client.open(OpenRequest("hello.dex", helloDexBytes()))
        val results: List<Any?> = List(60) { i ->
            async {
                if (i % 6 == 0) {
                    client.open(OpenRequest("hello.dex", helloDexBytes()))
                } else {
                    runCatching { client.code(NodeId("cls:HelloWorld"), CodeView.JAVA) }.getOrThrow()
                }
            }
        }.awaitAll()
        val docs = results.filterIsInstance<CodeDocument>()
        assertTrue(docs.isNotEmpty(), "some decompiles should have produced documents")
        // No corrupted/empty result slipped through the race — every doc is the real HelloWorld.
        assertTrue(
            docs.all { doc -> doc.lines.flatMap { it.tokens }.any { "Hello, World!" in it.text } },
            "every rendered document must be a valid HelloWorld",
        )
    }
}
