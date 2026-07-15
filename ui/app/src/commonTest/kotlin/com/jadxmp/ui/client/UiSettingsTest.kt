package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure, hand-rolled settings codec (no serialization dependency). Round-trip over every field
 * combination is the crux; the rest pin the tolerant parse (null/blank/garbage/unknown keys/unknown
 * enum names all degrade to defaults, never throw) and the exact on-disk shape.
 */
class UiSettingsTest {

    @Test
    fun roundTripsEveryFieldCombination() {
        for (theme in ThemeMode.entries) {
            for (flatten in listOf(true, false)) {
                for (view in CodeView.entries) {
                    val settings = UiSettings(theme, flatten, view)
                    assertEquals(settings, UiSettings.parse(settings.serialize()), "round-trip of $settings")
                }
            }
        }
    }

    @Test
    fun serializesToAFlatAllStringJsonObject() {
        val settings = UiSettings(ThemeMode.DARK, flattenPackages = true, preferredView = CodeView.KOTLIN)
        assertEquals(
            """{"themeMode":"DARK","flattenPackages":"true","preferredView":"KOTLIN"}""",
            settings.serialize(),
        )
    }

    @Test
    fun parseOfNullBlankOrGarbageFallsBackToDefaults() {
        assertEquals(UiSettings(), UiSettings.parse(null))
        assertEquals(UiSettings(), UiSettings.parse(""))
        assertEquals(UiSettings(), UiSettings.parse("   "))
        assertEquals(UiSettings(), UiSettings.parse("this is not json"))
    }

    @Test
    fun parseIgnoresUnknownKeysAndFillsMissingWithDefaults() {
        val parsed = UiSettings.parse("""{"preferredView":"SMALI","somethingElse":"x"}""")
        assertEquals(UiSettings(ThemeMode.SYSTEM, flattenPackages = false, preferredView = CodeView.SMALI), parsed)
    }

    @Test
    fun parseToleratesUnknownEnumNames() {
        val parsed = UiSettings.parse("""{"themeMode":"NEON","preferredView":"COBOL"}""")
        assertEquals(ThemeMode.SYSTEM, parsed.themeMode)
        assertEquals(CodeView.JAVA, parsed.preferredView)
    }

    @Test
    fun resolveDarkFoldsTheSystemFlag() {
        assertEquals(true, ThemeMode.DARK.resolveDark(systemDark = false))
        assertEquals(false, ThemeMode.LIGHT.resolveDark(systemDark = true))
        assertEquals(true, ThemeMode.SYSTEM.resolveDark(systemDark = true))
        assertEquals(false, ThemeMode.SYSTEM.resolveDark(systemDark = false))
    }
}
