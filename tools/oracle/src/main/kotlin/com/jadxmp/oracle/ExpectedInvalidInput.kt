package com.jadxmp.oracle

import java.security.MessageDigest

/**
 * The one ART-verified invalid original fixture, explicitly approved as a required diagnostic.
 * This is not an output-parity claim or a general exemption for malformed bytecode. See
 * docs/INVALID-BYTECODE.md for the unchanged input, verifier evidence and policy decision.
 */
object ExpectedInvalidInput {
    const val SAMPLE = "trycatch/TestTryCatchMultiException2.smali"
    const val SHA256 = "eaad95f9be98a119cf8aaf61ca7c736dc9ed4a7271f03d0e0966e856e0536301"
    const val CLASS_NAME = "trycatch.TestTryCatchMultiException2"
    const val DIAGNOSTIC = "JADXMP ERROR: invalid register type: reference value reaches primitive return at 28"

    fun assess(sample: String, input: ByteArray, result: DecompilationResult): InvalidInputEvidence? {
        if (sample != SAMPLE) return null
        val problems = buildList {
            val hash = MessageDigest.getInstance("SHA-256").digest(input)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (hash != SHA256) add("input SHA-256 differs from the ART-verified fixture")
            if (result.inputName != sample) add("result belongs to another input")
            if (result.reportedErrors != 1) add("expected exactly one reported error, got ${result.reportedErrors}")
            val cls = result.classes.singleOrNull()
            if (cls?.fullName != CLASS_NAME) add("expected the original class output")
            val source = cls?.source.orEmpty()
            if (source.lineSequence().count { it.trim() == "// $DIAGNOSTIC" } != 1) {
                add("required invalid-return diagnostic missing or duplicated")
            }
            // Remove only the matched line: prefix replacement could hide a second, longer error.
            val remaining = source.lineSequence().filter { it.trim() != "// $DIAGNOSTIC" }.joinToString("\n")
            if (ErrorMarkers.JADXMP.any { it in remaining }) add("additional failure marker in output")
        }
        return InvalidInputEvidence(sample, problems)
    }
}

/** Evidence belongs to the exact input, independently of its ordinary measured compilation signals. */
class InvalidInputEvidence internal constructor(
    private val sample: String,
    problems: List<String>,
) {
    val problems: List<String> = problems.toList()

    internal fun accepts(name: String, reference: SignalScore, candidate: SignalScore): Boolean =
        sample == name && problems.isEmpty() &&
            reference == SignalScore(true, true, null) && candidate == SignalScore(false, false, null)
}
