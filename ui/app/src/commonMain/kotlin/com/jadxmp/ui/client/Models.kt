package com.jadxmp.ui.client

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/** Stable identity for any navigable node (package, class, member, resource, file). */
@JvmInline
value class NodeId(val value: String)

/** What a tree row represents. Drives the row icon/badge and semantics. */
enum class NodeKind {
    PACKAGE,
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION_CLASS,
    METHOD,
    FIELD,
    DIRECTORY,
    FILE,
    RESOURCE,
    IMAGE,
    ;

    val isType: Boolean get() = this == CLASS || this == INTERFACE || this == ENUM || this == ANNOTATION_CLASS
}

/** The two top-level trees jadx-gui offers. */
enum class TreeKind { CLASSES, RESOURCES }

/**
 * Source-level access visibility of a class/member tree node, projected from the engine's `Modifier`
 * set. Drives a small colored overlay on the node badge (jadx-gui shows public/protected/private/
 * package-private glyphs). `null` on a node means "unknown / not applicable" (packages, resources,
 * or a class the cheap no-decompile lookup could not classify) → no overlay.
 */
enum class Visibility { PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE }

/** Source rendering a node can be shown in. */
enum class CodeView { JAVA, KOTLIN, SMALI }

/**
 * One node in a tree. [hasChildren] lets the UI show an expander without eagerly loading children
 * (children come from [DecompilerClient.childNodes] on demand — important on single-threaded wasm).
 */
@Immutable
data class TreeNode(
    val id: NodeId,
    val label: String,
    val kind: NodeKind,
    val hasChildren: Boolean,
    /** e.g. a method descriptor or field type shown dimmed after the label. */
    val secondary: String? = null,
    /**
     * Access visibility of a class/member row, for the badge overlay. `null` for packages/resources or
     * when the client could not classify it — those render no overlay. Optional + defaulted so existing
     * node constructions are unaffected.
     */
    val visibility: Visibility? = null,
)

/**
 * A classified source token. This is the *stub* shape of the engine's per-offset CodeMetadata:
 * [kind] drives highlighting and [definition] drives jump-to-definition (null = not navigable).
 * The real engine will supply richer offset→annotation data; the code viewer consumes this shape.
 */
@Immutable
data class CodeToken(
    val text: String,
    val kind: TokenKind,
    val definition: NodeId? = null,
)

enum class TokenKind {
    PLAIN,
    KEYWORD,
    TYPE,
    STRING,
    NUMBER,
    COMMENT,
    ANNOTATION,
    FIELD,
    METHOD,
    PUNCTUATION,
}

@Immutable
data class CodeLine(val number: Int, val tokens: List<CodeToken>)

@Immutable
data class CodeDocument(
    val nodeId: NodeId,
    val title: String,
    val view: CodeView,
    val lines: List<CodeLine>,
) {
    val lineCount: Int get() = lines.size
    fun plainText(): String = lines.joinToString("\n") { line -> line.tokens.joinToString("") { it.text } }
}

/**
 * Request to open an input container (apk/dex/jar). [name] labels the project; [bytes] carries the
 * file contents for engine-backed clients (a shell reads the picked file and supplies them). [bytes]
 * is null for the in-memory stub, which needs no real input. `ui:app` never does file IO itself, so
 * bytes always arrive pre-read from a platform shell — keeping this wasm-safe.
 */
@Immutable
data class OpenRequest(val name: String, val bytes: ByteArray? = null)

/** Lifecycle of a decompiler session, surfaced to the shell for empty/loading/error states. */
@Immutable
sealed interface SessionState {
    data object Empty : SessionState
    data class Loading(val message: String, val progress: Float? = null) : SessionState
    data class Ready(val projectName: String, val classCount: Int) : SessionState
    data class Failed(val message: String) : SessionState
}

enum class SearchScope { CLASS, METHOD, FIELD, CODE, RESOURCE }

@Immutable
data class SearchQuery(
    val text: String,
    val scopes: Set<SearchScope> = setOf(SearchScope.CLASS, SearchScope.METHOD, SearchScope.FIELD),
    val useRegex: Boolean = false,
    val ignoreCase: Boolean = true,
)

@Immutable
data class SearchResult(
    val nodeId: NodeId,
    val title: String,
    val subtitle: String,
    val kind: NodeKind,
    val line: Int? = null,
)

@Immutable
data class SearchResults(val query: SearchQuery, val matches: List<SearchResult>)
