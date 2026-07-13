package com.jadxmp.codegen

/**
 * Per-offset metadata attached to generated source text. **jadx: ICodeAnnotation (+ AnnType)**
 *
 * Every meaningful token the backend emits (a type name, a method call, a variable, the closing brace
 * of a body) records one of these against the *character offset* at which the token begins. The GUI's
 * custom Compose code viewer consumes this directly — no re-lexing — to drive syntax highlighting,
 * jump-to-definition, and find-usages. Because those features are only as correct as these offsets,
 * the backend must attach them precisely.
 *
 * The four primary categories the writer records are **definition** ([DefinitionAnnotation]),
 * **reference** ([ReferenceAnnotation]), **variable** ([VariableAnnotation]) and **node-end**
 * ([NodeEndAnnotation]); [BytecodeOffsetAnnotation] additionally links a source position back to the
 * bytecode it came from (jadx: OFFSET), complementing the coarser line map.
 */
sealed interface CodeAnnotation

/**
 * Marks the *declaration site* of a class, method, field or variable — the target a
 * jump-to-definition lands on. jadx: NodeDeclareRef (AnnType.DECLARATION).
 */
data class DefinitionAnnotation(val ref: CodeNodeRef) : CodeAnnotation

/**
 * Marks a *use* of a class, method or field defined elsewhere (possibly in another, unloaded class).
 * jadx: a plain node ref with AnnType.CLASS / METHOD / FIELD.
 */
data class ReferenceAnnotation(val ref: CodeNodeRef) : CodeAnnotation

/**
 * Marks an occurrence of a local variable. [declaration] is true at the variable's declaring
 * position and false at each subsequent use, so find-usages and rename can treat both alike while the
 * viewer still knows which occurrence introduces the name. jadx: VarRef (AnnType.VAR_REF) / VarNode.
 */
data class VariableAnnotation(val ref: VarRef, val declaration: Boolean) : CodeAnnotation

/**
 * Marks the end (the closing `}`) of a class or method body. jadx: NodeEnd (AnnType.END).
 *
 * [CodeMetadata.nodeAt] uses these as nesting markers: walking upward from a caret position, each end
 * cancels one enclosing declaration, so the innermost still-open class/method is found correctly.
 */
data object NodeEndAnnotation : CodeAnnotation

/**
 * Links a source position to the original bytecode [bytecodeOffset] it was emitted from (jadx:
 * InsnCodeOffset / AnnType.OFFSET). Powers "show bytecode for this line" and finer debugging than the
 * line map alone.
 */
data class BytecodeOffsetAnnotation(val bytecodeOffset: Int) : CodeAnnotation

/** The kind of program element a [CodeNodeRef] identifies. jadx: ICodeAnnotation.AnnType (node subset). */
enum class RefKind { CLASS, METHOD, FIELD, VARIABLE, PACKAGE }

/**
 * Identifies the program element an annotation points at — the jump-to-definition target and the
 * find-usages key. jadx: ICodeNodeRef.
 *
 * References carry *symbolic identity* (fully-qualified names / a stable variable id) rather than a
 * direct pointer to an IR node, so an annotation can equally target a class in the current model or an
 * external symbol (`java.lang.String.valueOf`) that was never loaded. Two occurrences of the same
 * element produce equal refs, which is exactly what find-usages needs.
 */
sealed interface CodeNodeRef {
    val refKind: RefKind
}

/** A class or interface, by fully-qualified name (binary names use `.`, not `$`). */
data class ClassNodeRef(val fullName: String) : CodeNodeRef {
    override val refKind: RefKind get() = RefKind.CLASS
}

/** A method, keyed by owner + name + erased argument type descriptors (enough to disambiguate overloads). */
data class MethodNodeRef(
    val ownerClass: String,
    val name: String,
    val argTypeDescriptors: List<String>,
) : CodeNodeRef {
    override val refKind: RefKind get() = RefKind.METHOD
}

/** A field, keyed by owner + name. */
data class FieldNodeRef(val ownerClass: String, val name: String) : CodeNodeRef {
    override val refKind: RefKind get() = RefKind.FIELD
}

/**
 * A local variable, identified by a [id] that is stable and unique *within its method*. The generator
 * assigns ids sequentially as it first encounters each variable, so output (and therefore metadata) is
 * deterministic.
 */
data class VarRef(val id: Int, val name: String) : CodeNodeRef {
    override val refKind: RefKind get() = RefKind.VARIABLE
}

/** A package, by dotted name. */
data class PackageRef(val name: String) : CodeNodeRef {
    override val refKind: RefKind get() = RefKind.PACKAGE
}
