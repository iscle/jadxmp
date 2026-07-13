import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// core:api — the public facade: Decompiler, DecompilerArgs, coroutine scheduler, plugin registry.
// Wires input -> ir -> pipeline -> codegen into an end-to-end decompile. The only module UI/tools
// depend on. commonMain, wasm-safe. See docs/ARCHITECTURE.md.
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
        namespace = "com.jadxmp.api"
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
            implementation(projects.core.inputDex)
            implementation(projects.core.pipeline)
            // api: core:codegen's CodeMetadata is exposed on DecompiledClass (UI consumes it).
            api(projects.core.codegen)
            implementation(projects.core.codegenJava)
            implementation(projects.core.codegenKotlin)
            implementation(projects.core.binaryIo)
            // Android resource decoding (ARSC + binary XML) surfaced through the facade's resources API.
            implementation(projects.core.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(projects.core.testSupport)
        }
    }
}
