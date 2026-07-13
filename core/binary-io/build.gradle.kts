import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// core:binary-io — the only engine module that reads bytes / touches a filesystem.
// commonMain-first, must compile for wasmJs. No java.* in common code. See docs/MODULE-LAYOUT.md.
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
        namespace = "com.jadxmp.io"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // kotlinx-io backs the FileSystem/byte-source abstraction. This is the ONLY module
            // allowed to depend on kotlinx-io; everything downstream reads through our interfaces.
            api(libs.kotlinx.io.core)
            // Kompress supplies DEFLATE + ZIP central-directory handling; we wrap it with our own
            // zip-slip/zip-bomb guard. Kept internal (implementation) so it stays confined here.
            implementation(libs.kompress.zip)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
