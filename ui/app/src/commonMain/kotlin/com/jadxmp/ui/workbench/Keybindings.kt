package com.jadxmp.ui.workbench

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The global keyboard-shortcut layer for the workbench (jadx-gui parity gap P0#1).
 *
 * A [ShortcutAction] names *what* the user wants to do; the [DefaultKeymap] maps each action to one or
 * more [KeyStroke]s. This split is deliberate so a future shortcuts-editor can rebind an action to a
 * different stroke without touching the dispatch site: replace the map, keep the [ShortcutAction] `when`.
 *
 * ## Cross-platform accelerator
 * A stroke's [KeyStroke.primary] flag means "the platform command accelerator": **Ctrl on Windows/Linux
 * OR Cmd (Meta) on macOS**. [resolveShortcut] treats either as satisfying it (`isCtrlPressed ||
 * isMetaPressed`), so one binding covers both platforms with no `expect/actual`. Modifiers are matched
 * *exactly* (a stroke with `shift = false` will not fire when Shift is held), so `⌘F` (Find) and
 * `⌘⇧F` (global Search) never collide.
 *
 * Pure and Compose-UI-only (no `java.*`), so it compiles for jvm + wasmJs + js. The matching core
 * ([matchShortcut]) is decoupled from [KeyEvent] so it is unit-tested directly with plain values.
 */
enum class ShortcutAction {
    /** Ctrl/Cmd+O — bring up the file picker (or the sample project with no opener). */
    OpenFile,

    /** Ctrl/Cmd+F — toggle the in-editor Find bar over the active document. */
    FindInFile,

    /** Ctrl/Cmd+Shift+F or Ctrl/Cmd+N — open/focus the global search panel. */
    GlobalSearch,

    /** Ctrl/Cmd+W — close the active editor tab. */
    CloseTab,

    /** Ctrl/Cmd+S — save the active document's rendered text to a file. */
    SaveFile,

    /** Alt+Left or Cmd/Ctrl+[ — navigate back in history. */
    GoBack,

    /** Alt+Right or Cmd/Ctrl+] — navigate forward in history. */
    GoForward,

    /** Esc — close the Find bar / search panel if open, else go back. */
    Escape,
}

/**
 * A modifier-qualified key. [primary] is the platform command accelerator (Ctrl on Windows/Linux, Cmd
 * on macOS — see [resolveShortcut]); [shift]/[alt] are matched exactly. A [ShortcutAction] can bind
 * several strokes (e.g. `Alt+Left` and `Cmd+[` both mean [ShortcutAction.GoBack]).
 */
@Immutable
data class KeyStroke(
    val key: Key,
    val primary: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** Exact match against a decoded key + modifier state (see [matchShortcut]). */
    internal fun matches(key: Key, primaryDown: Boolean, shift: Boolean, alt: Boolean): Boolean =
        this.key == key && this.primary == primaryDown && this.shift == shift && this.alt == alt
}

/**
 * The default action→strokes binding. A [LinkedHashMap] (via [mapOf]) so iteration is deterministic;
 * combined with exact modifier matching this makes resolution unambiguous. A shortcuts-editor would
 * swap this for a user-tuned copy.
 */
val DefaultKeymap: Map<ShortcutAction, List<KeyStroke>> = mapOf(
    ShortcutAction.OpenFile to listOf(KeyStroke(Key.O, primary = true)),
    ShortcutAction.FindInFile to listOf(KeyStroke(Key.F, primary = true)),
    ShortcutAction.GlobalSearch to listOf(
        KeyStroke(Key.F, primary = true, shift = true),
        KeyStroke(Key.N, primary = true),
    ),
    ShortcutAction.CloseTab to listOf(KeyStroke(Key.W, primary = true)),
    ShortcutAction.SaveFile to listOf(KeyStroke(Key.S, primary = true)),
    ShortcutAction.GoBack to listOf(
        KeyStroke(Key.DirectionLeft, alt = true),
        KeyStroke(Key.LeftBracket, primary = true),
    ),
    ShortcutAction.GoForward to listOf(
        KeyStroke(Key.DirectionRight, alt = true),
        KeyStroke(Key.RightBracket, primary = true),
    ),
    ShortcutAction.Escape to listOf(KeyStroke(Key.Escape)),
)

/**
 * Resolve a decoded key + modifier state to an action, or null when nothing binds it. Pure — no
 * [KeyEvent] — so it is unit-testable with plain [Key] constants on every target. [primaryDown] should
 * already fold Ctrl/Cmd together.
 */
internal fun matchShortcut(
    key: Key,
    primaryDown: Boolean,
    shift: Boolean,
    alt: Boolean,
    keymap: Map<ShortcutAction, List<KeyStroke>> = DefaultKeymap,
): ShortcutAction? {
    for ((action, strokes) in keymap) {
        if (strokes.any { it.matches(key, primaryDown, shift, alt) }) return action
    }
    return null
}

/**
 * Map a raw Compose [KeyEvent] to a [ShortcutAction], or null. Only key-**down** events resolve (so an
 * action fires once, not again on release). Ctrl and Cmd are folded into the single "primary"
 * accelerator here, which is the one place platform key handling lives.
 */
fun resolveShortcut(event: KeyEvent, keymap: Map<ShortcutAction, List<KeyStroke>> = DefaultKeymap): ShortcutAction? {
    if (event.type != KeyEventType.KeyDown) return null
    return matchShortcut(
        key = event.key,
        primaryDown = event.isCtrlPressed || event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
        keymap = keymap,
    )
}
