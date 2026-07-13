package com.jadxmp.api

import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [DecompiledClass.fullName]/[DecompiledClass.simpleName] a Java result reports is the **emitted
 * source name** (what the file path/recompile uses), not the binary [IrClass.fullName]. Regression guard
 * for the `names` cluster: `class doWord` (binary `do`) must be written to `doWord.java`, and a
 * sanitized package must flow into the `.class` output path. Kotlin keeps the binary name (follow-up).
 */
class SourceNamingTest {

    private fun cls(fullName: String, root: IrRoot = IrRoot()): IrClass =
        IrClass(root, fullName, accessFlags = 0x0001, superType = IrType.OBJECT).also { root.addClass(it) }

    private fun decompiled(fullName: String) =
        DecompiledClass(fullName, "", ClassMetadata(code = null, errorCount = 0, fullyStructured = true))

    @Test
    fun reservedClassNameReportsTheAlias() {
        val c = cls("do")
        assertEquals("doWord", sourceFullName(c, OutputFormat.JAVA))
        assertEquals("doWord", decompiled(sourceFullName(c, OutputFormat.JAVA)).simpleName)
    }

    @Test
    fun reservedPackageSegmentsReportSanitizedPath() {
        val c = cls("do.if.A")
        assertEquals("doWord.ifWord.A", sourceFullName(c, OutputFormat.JAVA))
        // simpleName is the file base; the package path is the .class output directory.
        assertEquals("A", decompiled(sourceFullName(c, OutputFormat.JAVA)).simpleName)
    }

    @Test
    fun invalidCharClassNameReportsValidFileName() {
        assertEquals("do_", sourceFullName(cls("do-"), OutputFormat.JAVA))
        assertEquals("i_f", sourceFullName(cls("i-f"), OutputFormat.JAVA))
    }

    @Test
    fun collidingTopLevelClassesReportDistinctFileNames() {
        val root = IrRoot()
        val first = cls("pkg.a-b", root)
        val second = cls("pkg.a_b", root)
        assertEquals("pkg.a_b", sourceFullName(first, OutputFormat.JAVA))
        assertEquals("pkg.a_b2", sourceFullName(second, OutputFormat.JAVA))
    }

    @Test
    fun validNameIsReportedVerbatim() {
        val c = cls("com.example.Widget")
        assertEquals("com.example.Widget", sourceFullName(c, OutputFormat.JAVA))
        assertEquals(c.fullName, sourceFullName(c, OutputFormat.JAVA))
    }

    @Test
    fun kotlinKeepsTheBinaryNameForNow() {
        // The `.kt` backend has the same file-naming gap; fixing it is a tracked parallel follow-up.
        assertEquals("do", sourceFullName(cls("do"), OutputFormat.KOTLIN))
    }
}
