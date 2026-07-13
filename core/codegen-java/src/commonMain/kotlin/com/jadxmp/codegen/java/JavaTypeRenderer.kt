package com.jadxmp.codegen.java

import com.jadxmp.codegen.ImportCollector
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind
import com.jadxmp.ir.type.WildcardBound

/**
 * Renders an [IrType] as Java source, routing class names through the [imports] collector so they come
 * out short (with an import) or fully qualified on clash. **jadx: TypeGen**
 *
 * Partial/unknown types can still be present before type inference has resolved everything (the linear
 * fallback path runs pre-inference); they are rendered to a deterministic concrete representative so
 * the output at least compiles.
 */
internal class JavaTypeRenderer(private val imports: ImportCollector) {

    fun render(type: IrType): String = when (type) {
        is IrType.Primitive -> primitiveName(type.kind)
        is IrType.Object -> renderObject(type)
        is IrType.ArrayType -> render(type.element) + "[]"
        is IrType.TypeVariable -> JavaIdentifiers.sanitize(type.name)
        is IrType.Wildcard -> renderWildcard(type)
        is IrType.Unknown -> renderUnknown(type)
    }

    /** The class name (short or FQN) for [type], without generics; also registers the import. */
    fun classNameOf(type: IrType): String = when (type) {
        is IrType.Object -> JavaIdentifiers.sanitizeQualified(imports.useClass(type.className))
        is IrType.ArrayType -> classNameOf(type.element)
        else -> render(type)
    }

    private fun renderObject(type: IrType.Object): String {
        // Sanitize the displayed name segments (a class/package named `do`, `1a`, etc. must still emit
        // valid Java); the ImportCollector keeps the raw name for clash detection and identity.
        val name = JavaIdentifiers.sanitizeQualified(imports.useClass(type.className))
        if (type.generics.isEmpty()) return name
        return name + type.generics.joinToString(", ", "<", ">") { render(it) }
    }

    private fun renderWildcard(type: IrType.Wildcard): String = when (type.bound) {
        WildcardBound.UNBOUNDED -> "?"
        WildcardBound.EXTENDS -> "? extends " + render(type.boundType ?: IrType.OBJECT)
        WildcardBound.SUPER -> "? super " + render(type.boundType ?: IrType.OBJECT)
    }

    /** A deterministic concrete stand-in for a still-partial type. */
    private fun renderUnknown(type: IrType.Unknown): String {
        val kinds = type.possible
        // Prefer a reference if the value could be one (matches jadx defaulting ambiguous refs to Object).
        if (TypeKind.OBJECT in kinds || TypeKind.ARRAY in kinds) return "java.lang.Object".let { imports.useClass(it) }
        // Otherwise pick the first primitive by a fixed priority.
        for (k in PRIMITIVE_PRIORITY) if (k in kinds) return primitiveName(k)
        return "int"
    }

    private fun primitiveName(kind: TypeKind): String = when (kind) {
        TypeKind.BOOLEAN -> "boolean"
        TypeKind.CHAR -> "char"
        TypeKind.BYTE -> "byte"
        TypeKind.SHORT -> "short"
        TypeKind.INT -> "int"
        TypeKind.FLOAT -> "float"
        TypeKind.LONG -> "long"
        TypeKind.DOUBLE -> "double"
        TypeKind.VOID -> "void"
        TypeKind.OBJECT, TypeKind.ARRAY -> "java.lang.Object".let { imports.useClass(it) }
    }

    private companion object {
        val PRIMITIVE_PRIORITY = listOf(
            TypeKind.INT, TypeKind.LONG, TypeKind.DOUBLE, TypeKind.FLOAT,
            TypeKind.BOOLEAN, TypeKind.CHAR, TypeKind.BYTE, TypeKind.SHORT,
        )
    }
}
