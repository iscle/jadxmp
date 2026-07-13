package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.ImportCollector
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind
import com.jadxmp.ir.type.WildcardBound

/**
 * Renders an [IrType] as Kotlin source, routing class names through the [imports] collector so they
 * come out short (with an import) or fully qualified on clash. **jadx: TypeGen (Kotlin projection)**
 *
 * Kotlin's type surface differs from Java's:
 *  - JVM primitives map to their capitalized Kotlin names (`int`→`Int`, `boolean`→`Boolean`) and
 *    `void`→`Unit`;
 *  - `java.lang.Object`→`Any`;
 *  - a **primitive array** is a dedicated class (`int[]`→`IntArray`), while a reference array is the
 *    generic `Array<T>` (`String[]`→`Array<String>`, `int[][]`→`Array<IntArray>`);
 *  - a wildcard becomes use-site variance (`?`→`*`, `? extends T`→`out T`, `? super T`→`in T`).
 *
 * Nullability is intentionally NOT inferred yet — every reference is emitted as the non-null `T`
 * (see the module TODOs); guessing `?` unsoundly would break the "semantics-preserving" goal.
 *
 * Partial/unknown types can still be present pre-inference; they render to a deterministic concrete
 * representative so output is stable.
 */
internal class KotlinTypeRenderer(private val imports: ImportCollector) {

    fun render(type: IrType): String = when (type) {
        is IrType.Primitive -> primitiveName(type.kind)
        is IrType.Object -> renderObject(type)
        is IrType.ArrayType -> renderArray(type)
        is IrType.TypeVariable -> KotlinIdentifiers.sanitize(type.name)
        is IrType.Wildcard -> renderWildcard(type)
        is IrType.Unknown -> renderUnknown(type)
    }

    /** The class name (short or FQN) for [type], without generics; also registers the import. */
    fun classNameOf(type: IrType): String = when (type) {
        is IrType.Object -> if (type.isRootObject) "Any" else KotlinIdentifiers.sanitizeQualified(imports.useClass(type.className))
        is IrType.ArrayType -> render(type) // an array's "class" is the array type itself in Kotlin
        else -> render(type)
    }

    private fun renderArray(type: IrType.ArrayType): String {
        val element = type.element
        // A primitive-element array is a dedicated Kotlin class; everything else is Array<T>.
        if (element is IrType.Primitive) {
            return primitiveArrayName(element.kind)
        }
        return "Array<" + render(element) + ">"
    }

    private fun renderObject(type: IrType.Object): String {
        if (type.isRootObject) return "Any"
        val name = KotlinIdentifiers.sanitizeQualified(imports.useClass(type.className))
        if (type.generics.isEmpty()) return name
        return name + type.generics.joinToString(", ", "<", ">") { render(it) }
    }

    private fun renderWildcard(type: IrType.Wildcard): String = when (type.bound) {
        WildcardBound.UNBOUNDED -> "*"
        WildcardBound.EXTENDS -> "out " + render(type.boundType ?: IrType.OBJECT)
        WildcardBound.SUPER -> "in " + render(type.boundType ?: IrType.OBJECT)
    }

    /** A deterministic concrete stand-in for a still-partial type. */
    private fun renderUnknown(type: IrType.Unknown): String {
        val kinds = type.possible
        if (TypeKind.OBJECT in kinds || TypeKind.ARRAY in kinds) return "Any"
        for (k in PRIMITIVE_PRIORITY) if (k in kinds) return primitiveName(k)
        return "Int"
    }

    private fun primitiveName(kind: TypeKind): String = when (kind) {
        TypeKind.BOOLEAN -> "Boolean"
        TypeKind.CHAR -> "Char"
        TypeKind.BYTE -> "Byte"
        TypeKind.SHORT -> "Short"
        TypeKind.INT -> "Int"
        TypeKind.FLOAT -> "Float"
        TypeKind.LONG -> "Long"
        TypeKind.DOUBLE -> "Double"
        TypeKind.VOID -> "Unit"
        TypeKind.OBJECT, TypeKind.ARRAY -> "Any"
    }

    private fun primitiveArrayName(kind: TypeKind): String = when (kind) {
        TypeKind.BOOLEAN -> "BooleanArray"
        TypeKind.CHAR -> "CharArray"
        TypeKind.BYTE -> "ByteArray"
        TypeKind.SHORT -> "ShortArray"
        TypeKind.INT -> "IntArray"
        TypeKind.FLOAT -> "FloatArray"
        TypeKind.LONG -> "LongArray"
        TypeKind.DOUBLE -> "DoubleArray"
        // VOID/OBJECT/ARRAY are not real primitive-array elements; fall back to a generic array.
        else -> "Array<Any>"
    }

    private companion object {
        val PRIMITIVE_PRIORITY = listOf(
            TypeKind.INT, TypeKind.LONG, TypeKind.DOUBLE, TypeKind.FLOAT,
            TypeKind.BOOLEAN, TypeKind.CHAR, TypeKind.BYTE, TypeKind.SHORT,
        )
    }
}
