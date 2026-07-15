package com.jadxmp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.jadxmp.ui.workbench.JadxWorkbenchApp

/**
 * Android entry point. Launches ui:app's [JadxWorkbenchApp] — the same Compose workbench desktop and web
 * use — wiring the two platform seams this wave adds: an [AndroidSettingsStore] (SharedPreferences) so
 * theme / flatten / preferred-view persist across restarts, and an [AndroidFileSaver] (Downloads) so the
 * "Save file" action works.
 *
 * Note: a native file-open picker is not wired on android yet (a separate parity item), so the client is
 * left at its default in-memory sample project; the settings-persistence and save flows are fully
 * exercised against that sample.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val settingsStore = AndroidSettingsStore(this)
        val fileSaver = AndroidFileSaver(this)
        setContent {
            JadxWorkbenchApp(
                settingsStore = settingsStore,
                fileSaver = fileSaver,
            )
        }
    }
}

@Preview
@Composable
fun WorkbenchPreview() {
    JadxWorkbenchApp()
}
