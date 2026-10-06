package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpstreamJavaExtractorTest {
    @Test
    fun `preserves sample text binary identity and braces in literals while excluding harness`() {
        val sample = """
            @Deprecated public static class TestCls {
                String text = "} /* { */";
                public void check() { if (!text.startsWith("}")) throw new AssertionError(); }
                public static class Nested { }
            }
        """.trimIndent()
        val input = """
            package fixtures;
            import org.junit.jupiter.api.Test;
            public class Original extends MissingHarness {
                $sample
                @Test void harness() { missing(); }
            }
        """.trimIndent()
        val result = UpstreamJavaExtractor.extract(input)
        val extracted = result.sample!!
        assertEquals("fixtures.Original", extracted.source.fullName)
        assertEquals("fixtures.Original\$TestCls", extracted.checkClass)
        assertTrue(extracted.source.source.contains(sample))
        assertFalse(extracted.source.source.contains("MissingHarness"))
        assertFalse(extracted.source.source.contains("org.junit"))
        JavaCompilation.compile(listOf(extracted.source)).use {
            assertTrue(it.result.success, it.result.diagnostics.toString())
            assertEquals(CheckStatus.PASSED, CheckExecutor.run(extracted.checkClass, listOf(it.output)))
        }
    }

    @Test
    fun `maps only exact assertion facade import and keeps used dependencies`() {
        val input = """
            import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;
            import java.util.List;
            import java.util.Set;
            class Original { public static class TestCls {
                List<String> data;
                public void check() { assertThat(1).isEqualTo(1); }
            } }
        """.trimIndent()
        val extracted = UpstreamJavaExtractor.extract(input).sample!!
        assertTrue(extracted.source.source.contains("import static org.assertj.core.api.Assertions.assertThat;"))
        assertTrue(extracted.source.source.contains("import java.util.List;"))
        assertFalse(extracted.source.source.contains("import java.util.Set;"))
        assertEquals(1, extracted.transformations.size)
    }

    @Test
    fun `reports nonstatic samples syntax errors and absent samples explicitly`() {
        assertEquals(ExtractionStatus.UNSUPPORTED_SAMPLE, UpstreamJavaExtractor.extract(
            "class Original { public class TestCls {} }",
        ).status)
        assertEquals(ExtractionStatus.PARSE_ERROR, UpstreamJavaExtractor.extract(
            "class Original { public static class TestCls { void broken( } }",
        ).status)
        assertEquals(ExtractionStatus.NO_SAMPLE, UpstreamJavaExtractor.extract("class Original {} ").status)
    }

    @Test
    fun `check readiness requires public noarg void or boolean method`() {
        for (method in listOf("private void check() {}", "public void check(int i) {}", "public int check() { return 1; }")) {
            assertFalse(UpstreamJavaExtractor.extract(
                "class Original { public static class TestCls { $method } }",
            ).sample!!.hasCheck)
        }
        assertTrue(UpstreamJavaExtractor.extract(
            "class Original { public static class TestCls { public boolean check() { return true; } } }",
        ).sample!!.hasCheck)
    }
    @Test
    fun `inventory distinguishes failed assertions compilation and absent checks`() {
        val cases = mapOf(
            "public void check() { assertThat(1).isEqualTo(1); }" to OriginalFixtureStatus.CHECK_PASSED,
            "public void check() { assertThat(1).isEqualTo(2); }" to OriginalFixtureStatus.CHECK_FAILED,
            "public void check() { missing(); }" to OriginalFixtureStatus.COMPILE_FAILED,
            "public void method() {}" to OriginalFixtureStatus.COMPILED_NO_CHECK,
        )
        for ((body, expected) in cases) {
            val sample = UpstreamJavaExtractor.extract("""
                import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;
                class Original { public static class TestCls { $body } }
            """.trimIndent()).sample!!
            assertEquals(expected, UpstreamJavaInventory.validate(sample).status)
        }
    }

    @Test
    fun `extracts and executes pinned upstream array reordering check`() {
        val reference = Corpus.root().parentFile.resolve("reference/jadx")
        UpstreamJavaInventory.verifyReference(reference)
        val source = reference.resolve(
            "jadx-core/src/test/java/jadx/tests/integration/code/TestArrayAccessReorder.java",
        ).readText()
        val sample = UpstreamJavaExtractor.extract(source).sample!!
        assertEquals("jadx.tests.integration.code.TestArrayAccessReorder\$TestCls", sample.checkClass)
        assertEquals(OriginalFixtureStatus.CHECK_PASSED, UpstreamJavaInventory.validate(sample).status)
    }

}
