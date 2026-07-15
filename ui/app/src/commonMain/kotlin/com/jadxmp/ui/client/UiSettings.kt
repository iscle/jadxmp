package com.jadxmp.ui.client

import androidx.compose.runtime.Immutable

/**
 * How the workbench chooses light vs. dark. [SYSTEM] defers to the platform (`isSystemInDarkTheme()`),
 * so a fresh install follows the OS; toggling the theme pins an explicit [LIGHT]/[DARK] that persists.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Resolve a [ThemeMode] to an effective dark flag given the platform's current [systemDark]. */
fun ThemeMode.resolveDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The persisted UI preferences (jadx-gui parity gap P0#2 — settings were session-only before). Small
 * and deliberately flat; add fields with defaults as more preferences become persistent. Serialized by
 * the pure [SettingsCodec] in commonMain, with only the raw-string read/write living in each platform
 * shell's [SettingsStore] (desktop JSON file, web `localStorage`, android `SharedPreferences`), so this
 * whole type stays wasm-safe — no `java.*`, no reflection, no serialization dependency.
 */
@Immutable
data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val flattenPackages: Boolean = false,
    val preferredView: CodeView = CodeView.JAVA,
) {
    /** Serialize to the compact string a [SettingsStore] persists. */
    fun serialize(): String = SettingsCodec.serialize(this)

    companion object {
        /** Parse a persisted string (or `null`/blank/garbage) back to settings, falling back to defaults. */
        fun parse(raw: String?): UiSettings = SettingsCodec.parse(raw)
    }
}

/**
 * A tiny, dependency-free string<->[UiSettings] codec. We deliberately avoid pulling in
 * kotlinx-serialization for three fields; the format is a flat, all-string JSON object
 * (`{"themeMode":"DARK","flattenPackages":"true","preferredView":"JAVA"}`) so desktop's "JSON file"
 * is genuine JSON while parsing stays a trivial, tolerant key/value scan — no escaping is needed
 * because every value is a bare enum name or `true`/`false`. Pure and wasm-safe; unit-tested for
 * round-trip. Unknown keys are ignored and missing keys fall back to defaults, so old/new versions
 * interop and a corrupt file never throws.
 */
internal object SettingsCodec {
    private const val KEY_THEME = "themeMode"
    private const val KEY_FLATTEN = "flattenPackages"
    private const val KEY_VIEW = "preferredView"

    // Matches "key":"value" pairs. Keys are identifiers; values contain no quotes (enum names / booleans).
    private val PAIR = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"")

    fun serialize(settings: UiSettings): String = buildString {
        append('{')
        appendField(KEY_THEME, settings.themeMode.name)
        append(',')
        appendField(KEY_FLATTEN, settings.flattenPackages.toString())
        append(',')
        appendField(KEY_VIEW, settings.preferredView.name)
        append('}')
    }

    fun parse(raw: String?): UiSettings {
        if (raw.isNullOrBlank()) return UiSettings()
        val fields = PAIR.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }
        return UiSettings(
            themeMode = fields[KEY_THEME].toThemeMode(),
            flattenPackages = fields[KEY_FLATTEN]?.toBooleanStrictOrNull() ?: false,
            preferredView = fields[KEY_VIEW].toCodeView(),
        )
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        append('"').append(key).append("\":\"").append(value).append('"')
    }

    private fun String?.toThemeMode(): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.SYSTEM

    private fun String?.toCodeView(): CodeView =
        CodeView.entries.firstOrNull { it.name == this } ?: CodeView.JAVA
}
