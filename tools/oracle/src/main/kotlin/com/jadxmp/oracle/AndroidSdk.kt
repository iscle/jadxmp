package com.jadxmp.oracle

import java.io.File

/**
 * Locates `android.jar` so the recompile signal can resolve `android.*` framework types.
 *
 * WHY THIS MATTERS: most jadx smali samples reference `android.*`. Without the framework on the
 * recompile classpath, *both* decompilers' output fails to compile for a framework reason, and the
 * recompile signal collapses to a useless "both fail" tie — masking real regressions as PARITY. jadx's
 * own test harness puts android.jar on the classpath for exactly this reason; we mirror it.
 *
 * Resolution order: `-Djadxmp.android.jar` → `$ANDROID_HOME` / `$ANDROID_SDK_ROOT` → `local.properties`
 * `sdk.dir` (walked up from the working dir). Within an SDK, prefer the highest `platforms/android-<N>`
 * that has an `android.jar`. Returns null (never throws) if none is found — the harness then runs with
 * a blind recompile signal, which the scoreboard reports honestly as tied/indeterminate parity.
 */
object AndroidSdk {

    /** The recompile classpath entries (android.jar if found, else empty). */
    fun recompileClasspath(): List<File> = listOfNotNull(androidJar())

    fun androidJar(): File? {
        System.getProperty("jadxmp.android.jar")?.let { p ->
            File(p).takeIf { it.isFile }?.let { return it }
        }
        val sdk = sdkDir() ?: return null
        val platforms = File(sdk, "platforms").takeIf { it.isDirectory } ?: return null
        return platforms.listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
            ?.mapNotNull { dir -> File(dir, "android.jar").takeIf { it.isFile }?.let { dir.name to it } }
            ?.maxByOrNull { platformOrder(it.first) }
            ?.second
    }

    private fun sdkDir(): File? {
        System.getenv("ANDROID_HOME")?.let { File(it).takeIf { d -> d.isDirectory }?.let { return it } }
        System.getenv("ANDROID_SDK_ROOT")?.let { File(it).takeIf { d -> d.isDirectory }?.let { return it } }
        // local.properties sdk.dir, walked up from the working directory.
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val props = File(dir, "local.properties")
            if (props.isFile) {
                props.readLines()
                    .firstOrNull { it.trimStart().startsWith("sdk.dir=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.replace("\\:", ":")
                    ?.replace("\\\\", "\\")
                    ?.let { path -> File(path).takeIf { it.isDirectory }?.let { return it } }
            }
            dir = dir.parentFile
        }
        return null
    }

    /** Sort key so `android-37.0` > `android-36.1` > `android-36`; falls back to string order. */
    private fun platformOrder(name: String): Double =
        name.removePrefix("android-").toDoubleOrNull() ?: -1.0
}
