import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// core:resources — self-contained Android resource decoding: resources.arsc, binary XML /
// AndroidManifest, resource-id resolution, and a tiny multiplatform XML writer. commonMain,
// wasm-safe (no javax.xml / AWT). See docs/MODULE-LAYOUT.md.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    jvm()

    js {
        browser()
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    androidLibrary {
        namespace = "com.jadxmp.resources"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.binaryIo)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
