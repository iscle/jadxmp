import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// webApp — the browser shell for jadxmp. It renders the SAME Compose workbench (ui:app) as the
// desktop app, but compiled to wasmJs (priority) and js, running the real decompiler engine
// (core:api) fully client-side with no backend. See docs/ARCHITECTURE.md §1/§9.
//
// wasmJs is the primary target; the js target is kept compiling as a fallback. Both share the
// `webMain` source set (Kotlin's default hierarchy makes it the js+wasmJs parent), which also lets
// us use the kotlin-wrappers `web.*` browser API (libs.wrappers.browser) from a single source set.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // The shared workbench UI + the real engine facade, embedded directly (client-side).
            // Mirrors desktopApp: JadxWorkbenchApp(client = CoreApiDecompilerClient(), ...).
            implementation(projects.ui.app)
            implementation(projects.core.api)

            // Compose entry point (ComposeViewport) + remember.
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)

            // kotlin-wrappers browser DOM API (web.dom/web.html/web.file/web.blob) — used by
            // BrowserFileOpener to run an <input type=file> picker and read the bytes. Publishes a
            // shared webMain surface for both js and wasmJs.
            implementation(libs.wrappers.browser)

            // suspendCancellableCoroutine (file-picker callback → suspend) + Promise.await.
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
