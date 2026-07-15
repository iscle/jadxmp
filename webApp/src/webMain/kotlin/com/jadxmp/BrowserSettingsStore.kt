package com.jadxmp

import com.jadxmp.ui.client.SettingsStore
import com.jadxmp.ui.client.UiSettings
import web.storage.localStorage

/**
 * Browser [SettingsStore]: persists [UiSettings] in `localStorage` under a single key — the web
 * analogue of DesktopSettingsStore. ui:app's commonMain owns the pure, wasm-safe serialize/parse
 * (see [UiSettings]); this shell only does the raw string get/set through the kotlin-wrappers `web.*`
 * API (the same surface BrowserFileOpener uses).
 *
 * `localStorage` can throw (private-mode, disabled storage, quota), so both operations are wrapped:
 * a failed read degrades to defaults and a failed write is dropped rather than thrown (rule 4).
 */
class BrowserSettingsStore : SettingsStore {

    // Whole body guarded: the raw `localStorage` read AND the parse are wrapped so load() — which runs
    // during startup composition — degrades to defaults instead of crashing launch, even if a future
    // non-total parse throws (rule 4). Normal + empty-store cases are unchanged (missing key →
    // getItem returns null → parse(null) → defaults).
    override fun load(): UiSettings =
        runCatching { UiSettings.parse(localStorage.getItem(KEY)) }.getOrDefault(UiSettings())

    override fun save(settings: UiSettings) {
        runCatching { localStorage.setItem(KEY, settings.serialize()) }
    }

    private companion object {
        const val KEY = "jadxmp.ui-settings"
    }
}
