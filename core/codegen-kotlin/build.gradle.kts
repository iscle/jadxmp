import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// core:codegen-kotlin — the Kotlin source backend (region tree -> .kt), the user-facing dual-output
// differentiator. Shares the region-tree walk with core:codegen; only leaf emission differs.
// commonMain, wasm-safe.
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
        namespace = "com.jadxmp.codegen.kotlin"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.codegen)
            implementation(projects.core.ir)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(projects.core.testSupport)
        }
    }
}
