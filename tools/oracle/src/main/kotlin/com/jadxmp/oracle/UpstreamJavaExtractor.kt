package com.jadxmp.oracle

import com.sun.source.tree.ClassTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import java.net.URI
import javax.lang.model.element.Modifier
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

internal enum class ExtractionStatus { EXTRACTED, NO_SAMPLE, UNSUPPORTED_SAMPLE, PARSE_ERROR }

internal data class ExtractedJavaSample(
    val source: DecompiledClass,
    val checkClass: String,
    val hasCheck: Boolean,
    val transformations: List<String>,
)

internal data class JavaExtraction(
    val status: ExtractionStatus,
    val sample: ExtractedJavaSample? = null,
    val diagnostics: List<String> = emptyList(),
)

/** Extracts trusted test inputs only; no upstream harness implementation enters the generated unit. */
internal object UpstreamJavaExtractor {
    private const val ASSERTION_FACADE = "jadx.tests.api.utils.assertj.JadxAssertions.assertThat"
    private const val ASSERTJ_ASSERTION = "org.assertj.core.api.Assertions.assertThat"

    fun extract(source: String): JavaExtraction {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "A JDK is required for fixture extraction" }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val file = object : SimpleJavaFileObject(URI.create("string:///Fixture.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(file)) as JavacTask
            val unit = task.parse().single()
            val errors = diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }
                .map { "${it.lineNumber}: ${it.getMessage(null)}" }
            if (errors.isNotEmpty()) return JavaExtraction(ExtractionStatus.PARSE_ERROR, diagnostics = errors)
            val candidates = unit.typeDecls.filterIsInstance<ClassTree>().flatMap { owner ->
                owner.members.filterIsInstance<ClassTree>().filter { it.simpleName.toString() == "TestCls" }
                    .map { owner to it }
            }
            if (candidates.isEmpty()) return JavaExtraction(ExtractionStatus.NO_SAMPLE)
            if (candidates.size != 1) return unsupported("Multiple direct TestCls declarations")
            val (owner, sample) = candidates.single()
            if (owner.kind != Tree.Kind.CLASS || owner.typeParameters.isNotEmpty() ||
                sample.kind != Tree.Kind.CLASS || !sample.modifiers.flags.containsAll(setOf(Modifier.PUBLIC, Modifier.STATIC))) {
                return unsupported("Requires a public static TestCls inside a nongeneric class")
            }
            val positions = Trees.instance(task).sourcePositions
            val start = positions.getStartPosition(unit, sample)
            val end = positions.getEndPosition(unit, sample)
            if (start < 0 || end < start || end > source.length) return unsupported("Missing source positions")
            val names = mutableSetOf<String>()
            object : TreeScanner<Unit, Unit>() {
                override fun visitIdentifier(node: IdentifierTree, unused: Unit?) {
                    names += node.name.toString()
                    super.visitIdentifier(node, unused)
                }
            }.scan(sample, Unit)
            val transformations = mutableListOf<String>()
            val imports = unit.imports.mapNotNull { declaration ->
                val name = declaration.qualifiedIdentifier.toString()
                if (name.substringAfterLast('.') !in names && !name.endsWith(".*")) return@mapNotNull null
                val mapped = if (declaration.isStatic && name == ASSERTION_FACADE) {
                    transformations += "$ASSERTION_FACADE -> $ASSERTJ_ASSERTION"
                    ASSERTJ_ASSERTION
                } else name
                "import ${if (declaration.isStatic) "static " else ""}$mapped;"
            }
            val packageName = unit.packageName?.toString()
            val ownerName = owner.simpleName.toString()
            val fullName = listOfNotNull(packageName, ownerName).joinToString(".")
            val hasCheck = sample.members.filterIsInstance<MethodTree>().any {
                it.name.toString() == "check" && Modifier.PUBLIC in it.modifiers.flags &&
                    it.parameters.isEmpty() && it.returnType?.toString() in setOf("void", "boolean")
            }
            val extracted = buildString {
                if (packageName != null) appendLine("package $packageName;")
                imports.forEach { appendLine(it) }
                appendLine("public class $ownerName {")
                appendLine(source.substring(start.toInt(), end.toInt()))
                appendLine("}")
            }
            return JavaExtraction(ExtractionStatus.EXTRACTED, ExtractedJavaSample(
                DecompiledClass(fullName, extracted), "$fullName\$TestCls", hasCheck, transformations,
            ))
        }
    }

    private fun unsupported(reason: String) = JavaExtraction(ExtractionStatus.UNSUPPORTED_SAMPLE, diagnostics = listOf(reason))
}
