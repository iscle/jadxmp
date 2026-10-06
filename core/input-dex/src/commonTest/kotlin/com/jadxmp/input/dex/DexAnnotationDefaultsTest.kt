package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import kotlin.test.*

class DexAnnotationDefaultsTest {
    private val value = EncodedValue(EncodedValueType.INT, 42)
    private fun defaults(nestedType: String = "LTest;", visibility: AnnotationVisibility? = AnnotationVisibility.SYSTEM) = AnnotationData(
        "Ldalvik/annotation/AnnotationDefault;", visibility,
        mapOf("value" to EncodedValue(EncodedValueType.ANNOTATION, AnnotationData(nestedType, null, mapOf("number" to value)))),
    )

    @Test fun extractsOnlyExactDefaultAndKeepsAbsentSeparate() {
        val defaults = DexAnnotationDefaults("LTest;", listOf(defaults()), 0x2601) { listOf(member()) }
        assertEquals(value, defaults.value("number"))
        assertNull(defaults.value("other"))
        assertNull(DexAnnotationDefaults("LTest;", emptyList(), 0x2601) { listOf(member()) }.value("number"))
    }

    @Test fun rejectsUnknownDefaultNamesOrdinaryClassesAndParameterizedMembers() {
        val owner = DexAnnotationDefaults("LTest;", listOf(defaults()), 0x2601) { emptyList() }
        assertFailsWith<IllegalArgumentException> { owner.value("number") }
        val ordinary = DexAnnotationDefaults("LTest;", listOf(defaults()), 1) { listOf(member()) }
        assertFailsWith<IllegalArgumentException> { ordinary.value("number") }
        val withParameter = DexAnnotationDefaults("LTest;", listOf(defaults()), 0x2601) { listOf(member(listOf("I"))) }
        assertFailsWith<IllegalArgumentException> { withParameter.value("number") }
    }

    private fun member(params: List<String> = emptyList()) = DexMethodData(
        DexMethodRef("LTest;", "number", "I", params), 0x401, emptyList(), emptyList(), { null },
    )

    @Test fun rejectsDuplicateContainerWrongOwnerVisibilityAndPayload() {
        for (annotations in listOf(
            listOf(defaults(), defaults()), listOf(defaults("LOther;")),
            listOf(defaults(visibility = AnnotationVisibility.RUNTIME)),
            listOf(AnnotationData("Ldalvik/annotation/AnnotationDefault;", AnnotationVisibility.SYSTEM, mapOf("value" to value))),
        )) assertFailsWith<IllegalArgumentException> { DexAnnotationDefaults("LTest;", annotations, 0x2601) { listOf(member()) }.value("number") }
    }
    @Test fun ambiguousMembersAreNotChosenByName() {
        val defaults = DexAnnotationDefaults("LTest;", listOf(defaults()), 0x2601) { listOf(member(), member(listOf("I"))) }
        assertFailsWith<IllegalArgumentException> { defaults.value("number") }
        assertFailsWith<IllegalArgumentException> { defaults.value("number") } // cached failure
    }

    @Test fun lookupTextWorkIsChargedAcrossRepeatedUses() {
        val name = "a".repeat(60_000)
        val method = DexMethodData(DexMethodRef("LTest;", name, "I", emptyList()), 0x401, emptyList(), emptyList(), { null })
        val nested = AnnotationData("LTest;", null, mapOf(name to value))
        val container = AnnotationData("Ldalvik/annotation/AnnotationDefault;", AnnotationVisibility.SYSTEM,
            mapOf("value" to EncodedValue(EncodedValueType.ANNOTATION, nested)))
        val defaults = DexAnnotationDefaults("LTest;", listOf(container), 0x2601) { listOf(method) }
        repeat(5) { assertEquals(value, defaults.value(name)) }
        assertFailsWith<IllegalArgumentException> { repeat(20) { defaults.value(name) } }
    }

}
