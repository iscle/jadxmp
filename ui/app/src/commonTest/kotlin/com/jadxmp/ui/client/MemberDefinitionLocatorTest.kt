package com.jadxmp.ui.client

import com.jadxmp.codegen.CodeAnnotation
import com.jadxmp.codegen.CodeMetadata
import com.jadxmp.codegen.DefinitionAnnotation
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.ReferenceAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure key→line resolver ([MemberDefinitionLocator]) — given a decompiled source and
 * a hand-built [CodeMetadata], a member key must resolve to the 1-based line of its definition, and an
 * unresolvable key must yield null (open-without-scroll), never throw.
 */
class MemberDefinitionLocatorTest {

    // 1: class A {
    // 2:     int count;
    // 3:     void run() {
    // 4:     }
    // 5: }
    private val source = "class A {\n    int count;\n    void run() {\n    }\n}"

    private val fieldKey = FieldNodeRef("A", "count")
    private val methodKey = MethodNodeRef("A", "run", emptyList())

    private fun metadata(): CodeMetadata {
        val map = LinkedHashMap<Int, CodeAnnotation>()
        // Anchor each definition annotation at the offset where its name token begins.
        map[source.indexOf("count")] = DefinitionAnnotation(fieldKey)
        map[source.indexOf("run")] = DefinitionAnnotation(methodKey)
        // A same-key REFERENCE elsewhere must be ignored — only DEFINITIONs count.
        map[source.indexOf("A")] = ReferenceAnnotation(methodKey)
        return CodeMetadata.build(map, emptyMap())
    }

    @Test
    fun resolvesFieldAndMethodDefinitionsToTheirLines() {
        assertEquals(2, MemberDefinitionLocator.locate(source, metadata(), fieldKey))
        assertEquals(3, MemberDefinitionLocator.locate(source, metadata(), methodKey))
    }

    @Test
    fun unresolvableKeyYieldsNullNotAnException() {
        // A well-formed key with no matching definition annotation → no scroll.
        assertNull(MemberDefinitionLocator.locate(source, metadata(), FieldNodeRef("A", "missing")))
    }

    @Test
    fun nullMetadataYieldsNull() {
        assertNull(MemberDefinitionLocator.locate(source, null, fieldKey))
    }

    @Test
    fun offsetToLineCountsNewlines() {
        assertEquals(1, MemberDefinitionLocator.offsetToLine(source, 0))
        assertEquals(1, MemberDefinitionLocator.offsetToLine(source, -5), "negative offset clamps to line 1")
        assertEquals(3, MemberDefinitionLocator.offsetToLine(source, source.indexOf("run")))
        // An offset past the end clamps rather than over-counting.
        assertEquals(5, MemberDefinitionLocator.offsetToLine(source, source.length + 100))
    }
}
