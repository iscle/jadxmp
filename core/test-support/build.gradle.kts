import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// core:test-support — the shared CodeAssert DSL for commonTest across engine modules.
// Pure commonMain string logic (no IO, no java.*), so it runs on every target the engine
// targets. Becomes a commonTest dependency of the engine modules. See docs/TESTING-ORACLE.md.
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
        namespace = "com.jadxmp.testsupport"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // This module IS test infrastructure, so the DSL lives in commonMain (not commonTest)
            // in order to be consumed as a commonTest dependency by the engine modules. It depends
            // on kotlin-test so assertion failures surface as standard test failures on every target.
            implementation(libs.kotlin.test)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
