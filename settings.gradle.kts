rootProject.name = "Jadxmp"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")
include(":webApp")

// Compose Multiplatform GUI (see docs/UI-DESIGN.md)
include(":ui:app")

// Engine (see docs/MODULE-LAYOUT.md)
include(":core:binary-io")
include(":core:ir")
include(":core:input")
include(":core:input-dex")

include(":core:pipeline")
include(":core:codegen")
include(":core:codegen-java")
include(":core:codegen-kotlin")
include(":core:resources")
include(":core:api")

// Accuracy machinery (see docs/TESTING-ORACLE.md)
include(":core:test-support")
include(":tools:oracle")