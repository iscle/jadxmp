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

    override fun load(): UiSettings =
        UiSettings.parse(runCatching { localStorage.getItem(KEY) }.getOrNull())

    override fun save(settings: UiSettings) {
        runCatching { localStorage.setItem(KEY, settings.serialize()) }
    }

    private companion object {
        const val KEY = "jadxmp.ui-settings"
    }
}
