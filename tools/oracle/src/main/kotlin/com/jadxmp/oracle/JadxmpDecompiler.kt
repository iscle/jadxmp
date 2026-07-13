package com.jadxmp.oracle

import com.jadxmp.api.Decompiler as JadxmpEngine

/**
 * The **jadxmp side** of the differential oracle — now wired to `core:api`.
 *
 * Loads the container bytes into the jadxmp facade ([com.jadxmp.api.Decompiler]) and maps its result
 * classes onto the oracle's shared [DecompiledClass] / [DecompilationResult] contract, so [Scoreboard]
 * scores it with the exact same three signals as the reference.
 *
 * [DecompilationResult.reportedErrors] carries jadxmp's *structured* no-error signal: core:api's
 * `errorCount` is `sum of countErrors(cls)`, i.e. the count of `AttrFlag.HAS_ERROR` across every class
 * AND method (incl. RenderabilityGuard-flagged unrenderable methods). So the candidate's no-error signal
 * already inspects structured node error attributes, not just literal marker strings (SHOULD-FIX 3).
 * Broken output that the engine fails to flag HAS_ERROR (e.g. dangling statements with errorCount=0) is
 * caught instead by the recompile signal once android.jar is on the classpath.
 */
class JadxmpDecompiler : Decompiler {

    override val name: String = "jadxmp"

    override val errorMarkers: List<String> = ErrorMarkers.JADXMP

    override fun decompile(name: String, bytes: ByteArray): DecompilationResult {
        val engine = JadxmpEngine()
        engine.load(name, bytes)
        val result = engine.decompileAll()
        val classes = result.classes.map { DecompiledClass(it.fullName, it.code) }
        return DecompilationResult(inputName = name, classes = classes, reportedErrors = result.errorCount)
    }
}
