package com.jadxmp

import android.content.Context
import com.jadxmp.ui.client.SettingsStore
import com.jadxmp.ui.client.UiSettings

/**
 * Android [SettingsStore]: persists [UiSettings] in a private `SharedPreferences` file under a single
 * key — the android analogue of DesktopSettingsStore/BrowserSettingsStore. ui:app's commonMain owns the
 * pure, wasm-safe serialize/parse (see [UiSettings]); this shell only does the string get/put.
 *
 * Reads are served from SharedPreferences' in-memory cache (fast, safe on the main thread) and writes
 * use `apply()` (async, off the UI thread). Best-effort: a failure degrades to defaults / a dropped
 * save rather than throwing (rule 4).
 */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Whole body guarded: the raw read AND the parse are wrapped so load() — which runs during startup
    // composition — degrades to defaults instead of crashing launch, even if a future non-total parse
    // throws (rule 4). Normal + empty-store cases are unchanged (empty store → parse(null) → defaults).
    override fun load(): UiSettings =
        runCatching { UiSettings.parse(prefs.getString(KEY, null)) }.getOrDefault(UiSettings())

    override fun save(settings: UiSettings) {
        runCatching { prefs.edit().putString(KEY, settings.serialize()).apply() }
    }

    private companion object {
        const val PREFS = "jadxmp.ui"
        const val KEY = "ui-settings"
    }
}
