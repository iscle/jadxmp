package com.jadxmp.ui.workbench

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure shortcut-resolution core ([matchShortcut]) — the piece a [androidx.compose.ui.input.key.KeyEvent]
 * feeds after folding Ctrl/Cmd into [primaryDown]. Exact modifier matching is the crux (⌘F vs ⌘⇧F must
 * not collide), so most of these assert both the hit and the near-misses.
 */
class KeybindingsTest {

    @Test
    fun primaryAcceleratorMapsTheCoreActions() {
        // primaryDown=true stands in for BOTH Ctrl (win/linux) and Cmd (mac); the KeyEvent adapter folds them.
        assertEquals(ShortcutAction.OpenFile, resolve(Key.O, primary = true))
        assertEquals(ShortcutAction.FindInFile, resolve(Key.F, primary = true))
        assertEquals(ShortcutAction.CloseTab, resolve(Key.W, primary = true))
    }

    @Test
    fun globalSearchHasTwoBindings() {
        assertEquals(ShortcutAction.GlobalSearch, resolve(Key.F, primary = true, shift = true))
        assertEquals(ShortcutAction.GlobalSearch, resolve(Key.N, primary = true))
    }

    @Test
    fun findAndGlobalSearchDoNotCollideOnShift() {
        // ⌘F is Find, ⌘⇧F is global Search — the only difference is Shift, matched exactly.
        assertEquals(ShortcutAction.FindInFile, resolve(Key.F, primary = true, shift = false))
        assertEquals(ShortcutAction.GlobalSearch, resolve(Key.F, primary = true, shift = true))
    }

    @Test
    fun backAndForwardHaveArrowAndBracketBindings() {
        assertEquals(ShortcutAction.GoBack, resolve(Key.DirectionLeft, alt = true))
        assertEquals(ShortcutAction.GoBack, resolve(Key.LeftBracket, primary = true))
        assertEquals(ShortcutAction.GoForward, resolve(Key.DirectionRight, alt = true))
        assertEquals(ShortcutAction.GoForward, resolve(Key.RightBracket, primary = true))
    }

    @Test
    fun escapeNeedsNoModifier() {
        assertEquals(ShortcutAction.Escape, resolve(Key.Escape))
    }

    @Test
    fun unmodifiedLettersDoNotTriggerShortcuts() {
        // A bare "o"/"f"/"w" is ordinary typing — never a shortcut.
        assertNull(resolve(Key.O))
        assertNull(resolve(Key.F))
        assertNull(resolve(Key.W))
    }

    @Test
    fun extraModifiersAreNotForgiven() {
        // ⌘⌥F (an accidental Alt) is not Find; a stray Shift on Open is not Open.
        assertNull(resolve(Key.F, primary = true, alt = true))
        assertNull(resolve(Key.O, primary = true, shift = true))
    }

    @Test
    fun altArrowRequiresAltNotPrimary() {
        // Left arrow alone, or with the primary accelerator, is not Back (only Alt+Left is).
        assertNull(resolve(Key.DirectionLeft))
        assertNull(resolve(Key.DirectionLeft, primary = true))
    }

    private fun resolve(key: Key, primary: Boolean = false, shift: Boolean = false, alt: Boolean = false): ShortcutAction? =
        matchShortcut(key = key, primaryDown = primary, shift = shift, alt = alt)
}
