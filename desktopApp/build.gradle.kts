import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    // The real workbench + the engine facade. desktopApp is the JVM shell: it embeds core:api directly
    // and provides native file access; ui:app renders the same UI every platform shares.
    implementation(projects.ui.app)
    implementation(projects.core.api)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.jadxmp.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.jadxmp"
            packageVersion = "1.0.0"
        }
    }
}