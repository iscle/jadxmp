import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ui:app — the Compose Multiplatform GUI (design system, reusable components, app shell).
// All UI logic lives in commonMain and must compile for wasmJs (no java.* in commonMain).
// It depends on the engine ONLY through core:api (not yet created); until then it renders
// against the DecompilerClient stub defined in this module. See docs/UI-DESIGN.md and
// docs/MODULE-LAYOUT.md.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    androidLibrary {
        namespace = "com.jadxmp.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
        }
        commonMain.dependencies {
            // The one engine seam: ui:app talks to the decompiler only through core:api's public facade
            // (Decompiler, DecompiledClass, CodeMetadata). No core:ir/core:pipeline internals.
            implementation(projects.core.api)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // StateFlow / structured concurrency for view-model state. Multiplatform incl. wasmJs.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinx.coroutines.get()}")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // runTest/TestScope for the view-model (WorkbenchState) async behaviour tests. Multiplatform.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
