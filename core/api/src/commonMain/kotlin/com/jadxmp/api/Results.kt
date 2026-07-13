package com.jadxmp.api

import com.jadxmp.codegen.CodeMetadata

/**
 * One decompiled class: its **emitted source name** [fullName], the emitted [code], and [metadata].
 *
 * [fullName] is the sanitized source name the [code] actually declares (e.g. `doWord` for a class whose
 * binary name is the reserved word `do`), NOT the binary [com.jadxmp.ir.node.IrClass.fullName]. This is
 * deliberate: the oracle harness (`tools:oracle`) writes the source to `<package>/<simpleName>.java` and
 * checks the compiled `.class` lands at `<package>/<simpleName>.class`, so the reported name MUST agree
 * with the name the body declares or a valid class fails to recompile. Callers needing the binary
 * identity read it from the model ([com.jadxmp.ir.node.IrClass.fullName] / [Decompiler.classNames]).
 *
 * Shaped for two consumers: the oracle harness, which reads [fullName]/[code] to score the accuracy
 * signals, and a UI client, which reads [metadata] for highlighting and jump-to-definition.
 */
data class DecompiledClass(
    val fullName: String,
    val code: String,
    val metadata: ClassMetadata,
) {
    /** Simple (unqualified) class name — the expected `.java` file base name, matching the class body. */
    val simpleName: String get() = fullName.substringAfterLast('.')
}

/**
 * Per-class decompilation metadata.
 *
 * @property code the codegen offset→annotation metadata (definitions, references, variables, line
 *   map) that drives the UI code viewer; null only if codegen produced none.
 * @property errorCount number of nodes (the class and its methods) whose decompilation failed and
 *   carry an error attribute — the "no-error" accuracy signal is `errorCount == 0`.
 * @property fullyStructured false when any method contains multi-block control flow that has not been
 *   structured into a region tree (Phase-3). Such methods render best-effort and are generally **not**
 *   compilable — a flag for callers, not a silent failure.
 */
data class ClassMetadata(
    val code: CodeMetadata?,
    val errorCount: Int,
    val fullyStructured: Boolean,
)

/** Everything one [Decompiler] produced for a loaded input. */
data class DecompilationResult(
    val classes: List<DecompiledClass>,
    /** Total decompilation errors across all classes (sum of each class's [ClassMetadata.errorCount]). */
    val errorCount: Int,
) {
    val classNames: List<String> get() = classes.map { it.fullName }
}
