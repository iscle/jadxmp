import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// core:pipeline — the pass framework + analysis passes: IR decode, CFG + dominators, SSA,
// type inference (and later structuring). The engine "brains". commonMain, wasm-safe.
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
        namespace = "com.jadxmp.pipeline"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.ir)
            api(projects.core.input)
            // For the shared codegen-facing attribute keys (CodegenKeys.THROWS) the analysis passes
            // populate. core:codegen depends only on core:ir, so this stays acyclic.
            implementation(projects.core.codegen)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
