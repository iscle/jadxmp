package com.jadxmp.oracle

import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JavaPackageUnitCompilationTest {
    @Test fun reportsIdentifyTheSymmetricCompilerProfile() {
        val profile = "javac options=-proc:none -Xpkginfo:always"
        assertTrue(Scoreboard().render().contains(profile))
        assertTrue(renderSmaliReport(Scoreboard(), 0, emptyList(), emptyList()).contains(profile))
        assertTrue(UpstreamRoundTripReport.summary(emptyList(), 0, null).contains(profile))
    }

    @Test fun unannotatedPackageUnitProducesItsExactBinaryIdentity() {
        val unit = DecompiledClass("special.pkg3.package-info", "package special.pkg3;")
        assertFalse(legacyProducesPackageClass(unit), "retain the previous default javac measurement")
        JavaCompilation.compile(listOf(unit)).use { compiled ->
            assertTrue(compiled.result.success, compiled.result.diagnostics.toString())
            URLClassLoader(arrayOf(compiled.output.toURI().toURL()), null).use { loader ->
                val cls = loader.loadClass(unit.fullName)
                assertEquals(unit.fullName, cls.name)
                assertTrue(cls.isInterface)
                assertTrue(cls.declaredAnnotations.isEmpty())
                assertThrows(ClassNotFoundException::class.java) { loader.loadClass("special.pkg3.package_info") }
            }
        }
    }

    @Test fun absentAndWronglyNamedUnitsStillFailTheExactOutputCheck() {
        for (unit in listOf(
            DecompiledClass("special.pkg3.package-info", ""),
            DecompiledClass("special.pkg3.package-info", "// comment only"),
            DecompiledClass("package-info", "// default package comment only"),
            DecompiledClass("special.pkg3.package-info", "package wrong.pkg;"),
            DecompiledClass("special.pkg3.Missing", "package special.pkg3;"),
            DecompiledClass("special.pkg3.package-info", "package special.pkg3"),
        )) {
            assertFalse(AccuracySignals.recompiles(listOf(unit)).success, unit.toString())
        }
        val healthy = DecompiledClass("p.Healthy", "package p; public class Healthy {}")
        assertFalse(AccuracySignals.recompiles(listOf(healthy, DecompiledClass("p.package-info", "// empty"))).success)
    }

    @Test fun pinnedPackageUnitSourceAndNoErrorSignalAreUnchanged() {
        val reference = pinned("pkg3")
        assertEquals(0, reference.reportedErrors)
        val unit = reference.classes.single()
        assertEquals("special.pkg3.package-info", unit.fullName)
        assertEquals("\npackage special.pkg3;\n", unit.source)
        assertFalse(legacyProducesPackageClass(unit), "the legacy failure stays explicit")
        assertTrue(AccuracySignals.recompiles(reference.classes).success)
    }

    @Test fun missingPackageAnnotationDependencyRemainsACompilationFailure() {
        val reference = pinned("pkg2")
        assertEquals(0, reference.reportedErrors)
        assertTrue(reference.classes.single().source.contains("@ApiStatus.Internal"))
        val emptyClasspath = Files.createTempDirectory("jadxmp-package-classpath").toFile()
        try {
            val result = AccuracySignals.recompiles(reference.classes, listOf(emptyClasspath))
            assertFalse(result.success)
            assertTrue(result.diagnostics.any { it.contains("ApiStatus") || it.contains("org.jetbrains.annotations") }, result.diagnostics.toString())
        } finally { emptyClasspath.deleteRecursively() }
    }

    @Test fun earlierRenamedInterfaceCompilationDoesNotProvePackageUnitIdentity() {
        // Exact pre-annotation candidate shape: it compiled after losing the package annotation
        // and renaming the reserved binary identity. This was a spurious compilation improvement.
        val previous = DecompiledClass("special.pkg2.package_info", "package special.pkg2; interface package_info {}")
        JavaCompilation.compile(listOf(previous)).use { compiled ->
            assertTrue(compiled.result.success)
            assertTrue(compiled.output.resolve("special/pkg2/package_info.class").isFile)
            assertFalse(compiled.output.resolve("special/pkg2/package-info.class").exists())
        }
    }

    private fun pinned(name: String): DecompilationResult {
        val root = Corpus.root().parentFile
        val reference = root.resolve("reference/jadx")
        PinnedReference.verify(reference)
        val relative = "special/TestPackageInfoSupport/$name.smali"
        val original = reference.resolve("jadx-core/src/test/smali/$relative")
        val fenced = Corpus.smaliDir().resolve(relative)
        assertArrayEquals(original.readBytes(), fenced.readBytes())
        val dex = requireNotNull(SmaliAssembler.assemble(original).dex)
        return ReferenceDecompiler().decompile("$name.dex", dex)
    }

    /** Retained previous measurement, never used to grant the new profile a passing result. */
    private fun legacyProducesPackageClass(unit: DecompiledClass): Boolean {
        val directory = Files.createTempDirectory("jadxmp-legacy-package").toFile()
        try {
            val source = directory.resolve("src/${unit.fullName.replace('.', '/')}.java")
            source.parentFile.mkdirs()
            source.writeText(unit.source)
            val output = directory.resolve("classes").apply { mkdirs() }
            val status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", output.path, source.path)
            assertEquals(0, status)
            return output.resolve(unit.fullName.replace('.', '/') + ".class").isFile
        } finally { directory.deleteRecursively() }
    }
}
