package com.jadxmp.ui.client

/**
 * A small, deterministic fake project so the shell renders (tree + code + search) with no engine.
 * Pure and synchronous — [StubDecompilerClient] wraps it in suspend/StateFlow, and tests exercise it
 * directly. Replaced wholesale by the `core:api`-backed client later.
 */
object StubData {

    const val PROJECT_NAME: String = "sample-app.apk"

    // ── Class tree ────────────────────────────────────────────────────────────
    private val pkgApp = NodeId("pkg:com.example.app")
    private val pkgData = NodeId("pkg:com.example.app.data")

    private val clsMainActivity = NodeId("cls:com.example.app.MainActivity")
    private val clsStringUtils = NodeId("cls:com.example.app.StringUtils")
    private val clsUser = NodeId("cls:com.example.app.data.User")
    private val clsUserRepository = NodeId("cls:com.example.app.data.UserRepository")

    private val classRoots = listOf(
        TreeNode(pkgApp, "com.example.app", NodeKind.PACKAGE, hasChildren = true),
        TreeNode(pkgData, "com.example.app.data", NodeKind.PACKAGE, hasChildren = true),
    )

    private val children: Map<NodeId, List<TreeNode>> = buildMap {
        put(
            pkgApp,
            listOf(
                TreeNode(clsMainActivity, "MainActivity", NodeKind.CLASS, hasChildren = true),
                TreeNode(clsStringUtils, "StringUtils", NodeKind.CLASS, hasChildren = true),
            ),
        )
        put(
            pkgData,
            listOf(
                TreeNode(clsUser, "User", NodeKind.CLASS, hasChildren = true),
                TreeNode(clsUserRepository, "UserRepository", NodeKind.INTERFACE, hasChildren = true),
            ),
        )
        put(
            clsMainActivity,
            listOf(
                TreeNode(member(clsMainActivity, "binding"), "binding", NodeKind.FIELD, false, "ActivityMainBinding"),
                TreeNode(member(clsMainActivity, "onCreate"), "onCreate", NodeKind.METHOD, false, "(Bundle): void"),
                TreeNode(member(clsMainActivity, "setupViews"), "setupViews", NodeKind.METHOD, false, "(): void"),
            ),
        )
        put(
            clsStringUtils,
            listOf(
                TreeNode(member(clsStringUtils, "<init>"), "StringUtils", NodeKind.METHOD, false, "()"),
                TreeNode(member(clsStringUtils, "capitalize"), "capitalize", NodeKind.METHOD, false, "(String): String"),
            ),
        )
        put(
            clsUser,
            listOf(
                TreeNode(member(clsUser, "id"), "id", NodeKind.FIELD, false, "long"),
                TreeNode(member(clsUser, "name"), "name", NodeKind.FIELD, false, "String"),
                TreeNode(member(clsUser, "displayName"), "displayName", NodeKind.METHOD, false, "(): String"),
            ),
        )
        put(
            clsUserRepository,
            listOf(
                TreeNode(member(clsUserRepository, "findById"), "findById", NodeKind.METHOD, false, "(long): User"),
                TreeNode(member(clsUserRepository, "findAll"), "findAll", NodeKind.METHOD, false, "(): List<User>"),
            ),
        )
    }

    /** member NodeId → owning class NodeId, so code()/nav resolve members to their class document. */
    private val memberOwner: Map<NodeId, NodeId> = children
        .filterKeys { it.value.startsWith("cls:") }
        .flatMap { (owner, kids) -> kids.map { it.id to owner } }
        .toMap()

    private fun member(cls: NodeId, name: String): NodeId = NodeId("mbr:${cls.value.removePrefix("cls:")}#$name")

    // ── Resource tree ─────────────────────────────────────────────────────────
    private val resManifest = NodeId("res:AndroidManifest.xml")
    private val resDir = NodeId("res:res")
    private val resLayoutDir = NodeId("res:res/layout")
    private val resLayoutMain = NodeId("res:res/layout/activity_main.xml")
    private val resDrawableDir = NodeId("res:res/drawable")
    private val resIcon = NodeId("res:res/drawable/ic_launcher.png")

    private val resourceRoots = listOf(
        TreeNode(resManifest, "AndroidManifest.xml", NodeKind.FILE, hasChildren = false),
        TreeNode(resDir, "res", NodeKind.DIRECTORY, hasChildren = true),
    )

    private val resourceChildren: Map<NodeId, List<TreeNode>> = mapOf(
        resDir to listOf(
            TreeNode(resLayoutDir, "layout", NodeKind.DIRECTORY, hasChildren = true),
            TreeNode(resDrawableDir, "drawable", NodeKind.DIRECTORY, hasChildren = true),
        ),
        resLayoutDir to listOf(
            TreeNode(resLayoutMain, "activity_main.xml", NodeKind.RESOURCE, hasChildren = false),
        ),
        resDrawableDir to listOf(
            TreeNode(resIcon, "ic_launcher.png", NodeKind.IMAGE, hasChildren = false),
        ),
    )

    // ── Public queries (pure) ─────────────────────────────────────────────────
    fun roots(tree: TreeKind): List<TreeNode> = when (tree) {
        TreeKind.CLASSES -> classRoots
        TreeKind.RESOURCES -> resourceRoots
    }

    fun childrenOf(parent: NodeId): List<TreeNode> =
        children[parent] ?: resourceChildren[parent] ?: emptyList()

    fun availableViews(node: NodeId): List<CodeView> {
        val cls = ownerClassOf(node)
        return if (cls != null) listOf(CodeView.JAVA, CodeView.KOTLIN, CodeView.SMALI) else listOf(CodeView.JAVA)
    }

    fun document(node: NodeId, view: CodeView): CodeDocument {
        val cls = ownerClassOf(node)
        if (cls != null) {
            val source = classSource(cls, view)
            return CodeDocument(node, titleOf(cls), view, StubHighlighter.highlight(source))
        }
        // Resource / file node
        val source = resourceSource(node)
        return CodeDocument(node, titleOf(node), CodeView.JAVA, StubHighlighter.highlight(source))
    }

    /** Every class/member/type node, used by the stub search. */
    fun allNodes(): List<TreeNode> = classRoots + children.values.flatten()

    /**
     * Resolve a stub member node to its owning class tab. The stub carries no per-offset metadata, so the
     * line is always null — the class opens without a scroll (honest: the stub can't place the caret).
     */
    fun memberLocation(memberNodeId: NodeId): MemberLocation? {
        val owner = memberOwner[memberNodeId] ?: return null
        return MemberLocation(owner, line = null)
    }

    private fun ownerClassOf(node: NodeId): NodeId? = when {
        node.value.startsWith("cls:") -> node
        else -> memberOwner[node]
    }

    private fun titleOf(node: NodeId): String {
        val v = node.value
        return when {
            v.startsWith("cls:") -> v.removePrefix("cls:").substringAfterLast('.')
            v.startsWith("res:") -> v.removePrefix("res:").substringAfterLast('/')
            else -> v.substringAfterLast(':')
        }
    }

    private fun classSource(cls: NodeId, view: CodeView): String {
        val java = javaSources[cls]
        return when (view) {
            CodeView.JAVA -> java ?: "// source unavailable"
            CodeView.KOTLIN -> kotlinSources[cls] ?: "// Kotlin view: engine follow-up (core:codegen-kotlin)\n${java ?: ""}"
            CodeView.SMALI -> smaliSources[cls] ?: "# smali view generated by the engine from the input model\n.class ${cls.value.removePrefix("cls:")}"
        }
    }

    private fun resourceSource(node: NodeId): String = when (node) {
        resManifest -> manifestXml
        resLayoutMain -> layoutXml
        resIcon -> "// binary image — rendered by the image viewer, not the code viewer"
        else -> "// ${titleOf(node)}"
    }

    // ── Fake sources ──────────────────────────────────────────────────────────
    private val javaSources: Map<NodeId, String> = mapOf(
        clsMainActivity to """
            package com.example.app;

            import android.os.Bundle;
            import androidx.appcompat.app.AppCompatActivity;

            public class MainActivity extends AppCompatActivity {
                private ActivityMainBinding binding;

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    this.binding = ActivityMainBinding.inflate(getLayoutInflater());
                    setContentView(this.binding.getRoot());
                    setupViews();
                }

                private void setupViews() {
                    // TODO: wire up click listeners
                    String greeting = StringUtils.capitalize("hello");
                    this.binding.title.setText(greeting);
                }
            }
        """.trimIndent(),
        clsStringUtils to """
            package com.example.app;

            public final class StringUtils {
                private StringUtils() {
                }

                public static String capitalize(String value) {
                    if (value == null || value.isEmpty()) {
                        return value;
                    }
                    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
                }
            }
        """.trimIndent(),
        clsUser to """
            package com.example.app.data;

            public class User {
                public final long id;
                public final String name;

                public User(long id, String name) {
                    this.id = id;
                    this.name = name;
                }

                public String displayName() {
                    return this.name + " #" + this.id;
                }
            }
        """.trimIndent(),
        clsUserRepository to """
            package com.example.app.data;

            import java.util.List;

            public interface UserRepository {
                User findById(long id);

                List<User> findAll();
            }
        """.trimIndent(),
    )

    private val kotlinSources: Map<NodeId, String> = mapOf(
        clsUser to """
            package com.example.app.data

            class User(val id: Long, val name: String) {
                fun displayName(): String = "${'$'}name #${'$'}id"
            }
        """.trimIndent(),
    )

    private val smaliSources: Map<NodeId, String> = mapOf(
        clsMainActivity to """
            .class public Lcom/example/app/MainActivity;
            .super Landroidx/appcompat/app/AppCompatActivity;
            .source "MainActivity.java"

            .field private binding:Lcom/example/app/databinding/ActivityMainBinding;

            .method protected onCreate(Landroid/os/Bundle;)V
                .registers 3
                invoke-super {p0, p1}, Landroidx/appcompat/app/AppCompatActivity;->onCreate(Landroid/os/Bundle;)V
                invoke-virtual {p0}, Lcom/example/app/MainActivity;->setupViews()V
                return-void
            .end method
        """.trimIndent(),
    )

    private val manifestXml: String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example.app">

            <application
                android:label="Example"
                android:theme="@style/AppTheme">
                <activity android:name=".MainActivity">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent()

    private val layoutXml: String = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical">

            <TextView
                android:id="@+id/title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />
        </LinearLayout>
    """.trimIndent()

    /** A naive substring search over class/member labels, for the stub search dialog. */
    fun search(query: SearchQuery): SearchResults {
        val needle = query.text.trim()
        if (needle.isEmpty()) return SearchResults(query, emptyList())
        val matcher: (String) -> Boolean = if (query.useRegex) {
            val regex = runCatching {
                Regex(needle, if (query.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            }.getOrNull()
            fun(text: String): Boolean = regex?.containsMatchIn(text) ?: false
        } else {
            fun(text: String): Boolean = text.contains(needle, ignoreCase = query.ignoreCase)
        }
        val matches = allNodes()
            .filter { node ->
                when (node.kind) {
                    NodeKind.PACKAGE -> SearchScope.CLASS in query.scopes
                    NodeKind.METHOD -> SearchScope.METHOD in query.scopes
                    NodeKind.FIELD -> SearchScope.FIELD in query.scopes
                    else -> SearchScope.CLASS in query.scopes
                }
            }
            .filter { matcher(it.label) }
            .map { node ->
                SearchResult(node.id, node.label, node.secondary ?: node.kind.name.lowercase(), node.kind)
            }
        return SearchResults(query, matches)
    }
}
