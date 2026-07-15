package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeView
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure "Save file" suggested-name derivation: `<SimpleName>.<ext>` for a class (extension from the
 * shown view) and the resource's own file name for a resource node, with graceful fallbacks for blank
 * id segments so it never yields an empty name.
 */
class SaveFileNameTest {

    @Test
    fun classNodeUsesSimpleNamePlusViewExtension() {
        assertEquals("Bar.java", suggestedFileName("cls:com.foo.Bar", CodeView.JAVA))
        assertEquals("Bar.kt", suggestedFileName("cls:com.foo.Bar", CodeView.KOTLIN))
        assertEquals("Bar.smali", suggestedFileName("cls:com.foo.Bar", CodeView.SMALI))
    }

    @Test
    fun topLevelClassWithoutAPackage() {
        assertEquals("Hello.java", suggestedFileName("cls:Hello", CodeView.JAVA))
    }

    @Test
    fun nestedClassKeepsTheDollarSegment() {
        assertEquals("Bar\$Inner.java", suggestedFileName("cls:com.foo.Bar\$Inner", CodeView.JAVA))
    }

    @Test
    fun resourceNodeUsesItsOwnFileName() {
        assertEquals("AndroidManifest.xml", suggestedFileName("res:AndroidManifest.xml", CodeView.JAVA))
        assertEquals("activity_main.xml", suggestedFileName("res:res/layout/activity_main.xml", CodeView.JAVA))
    }

    @Test
    fun blankSegmentsFallBackGracefully() {
        assertEquals("Class.java", suggestedFileName("cls:", CodeView.JAVA))
        assertEquals("resource.txt", suggestedFileName("res:", CodeView.JAVA))
    }

    @Test
    fun viewFileExtensions() {
        assertEquals("java", CodeView.JAVA.fileExtension())
        assertEquals("kt", CodeView.KOTLIN.fileExtension())
        assertEquals("smali", CodeView.SMALI.fileExtension())
    }
}
