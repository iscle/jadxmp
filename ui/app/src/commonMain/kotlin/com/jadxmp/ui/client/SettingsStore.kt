package com.jadxmp.ui.client

/**
 * Platform seam for persisting [UiSettings] across restarts, mirroring [FileOpener]'s injected pattern:
 * `ui:app`'s commonMain defines only the contract, and each shell supplies the storage (desktop = a
 * JSON file under the user config dir, web = `localStorage`, android = `SharedPreferences`). Injected
 * into [com.jadxmp.ui.workbench.WorkbenchState], which loads once on init and saves on every change
 * (theme / flatten / preferred view).
 *
 * Unlike [FileOpener] this is a plain `interface`, not a `fun interface`, because it has two operations
 * — a SAM can hold only one. Both are intentionally **synchronous**: reading/writing a handful of
 * key/values is a fast, local operation on every target (a small file, `localStorage`, or the
 * `SharedPreferences` in-memory cache), and [load] must return before first composition so the stored
 * theme can seed the very first frame. Shells swallow IO failures to defaults rather than throwing, so
 * a missing/corrupt store degrades to first-run defaults instead of crashing.
 */
interface SettingsStore {
    /** Read the persisted settings, or defaults when nothing is stored / the read fails. */
    fun load(): UiSettings

    /** Persist [settings]; a failure is swallowed (settings persistence is best-effort). */
    fun save(settings: UiSettings)
}
