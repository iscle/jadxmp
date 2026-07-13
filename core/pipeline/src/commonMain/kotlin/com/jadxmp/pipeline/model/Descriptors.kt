package com.jadxmp.pipeline.model

import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind

/**
 * Parses JVM/DEX **type descriptors** (the `I`, `Ljava/lang/String;`, `[[I` form that both input
 * parsers speak) into the `core:ir` [IrType] lattice.
 *
 * Descriptors are the single lingua franca of `core:input`: field/method refs, class names and the
 * `indexAsType` results all arrive as descriptors, so this is the one bridge from the input model's
 * string types into resolved [IrType]s. Generic signatures are **not** handled here — those are a
 * separate `Signature`-attribute concern layered on later; a descriptor gives the erased type.
 */
object Descriptors {

    /** Parse a single field/return/parameter type descriptor. */
    fun parseType(descriptor: String): IrType {
        require(descriptor.isNotEmpty()) { "empty type descriptor" }
        return parseAt(descriptor, 0).type
    }

    /**
     * Convert an internal class name descriptor (`Lcom/example/Foo;`) — or a bare internal name
     * (`com/example/Foo`) — into a resolved object [IrType] with a dotted class name.
     */
    fun parseClassType(descriptor: String): IrType {
        val internal = when {
            descriptor.startsWith("L") && descriptor.endsWith(";") -> descriptor.substring(1, descriptor.length - 1)
            descriptor.startsWith("[") -> return parseType(descriptor) // array-typed class ref
            else -> descriptor
        }
        return IrType.objectType(internalToDotted(internal))
    }

    /** `com/example/Foo` -> `com.example.Foo`; `$` kept (inner-class marker). */
    fun internalToDotted(internalName: String): String = internalName.replace('/', '.')

    private data class Parsed(val type: IrType, val next: Int)

    private fun parseAt(d: String, start: Int): Parsed {
        return when (d[start]) {
            'V' -> Parsed(IrType.VOID, start + 1)
            'Z' -> Parsed(IrType.BOOLEAN, start + 1)
            'B' -> Parsed(IrType.BYTE, start + 1)
            'S' -> Parsed(IrType.SHORT, start + 1)
            'C' -> Parsed(IrType.CHAR, start + 1)
            'I' -> Parsed(IrType.INT, start + 1)
            'J' -> Parsed(IrType.LONG, start + 1)
            'F' -> Parsed(IrType.FLOAT, start + 1)
            'D' -> Parsed(IrType.DOUBLE, start + 1)
            'L' -> {
                val end = d.indexOf(';', start)
                require(end >= 0) { "unterminated object descriptor in '$d'" }
                Parsed(IrType.objectType(internalToDotted(d.substring(start + 1, end))), end + 1)
            }
            '[' -> {
                val elem = parseAt(d, start + 1)
                Parsed(IrType.array(elem.type), elem.next)
            }
            else -> error("bad type descriptor '$d' at $start")
        }
    }

    /** The register-slot count a value of [type] occupies (long/double take 2, everything else 1). */
    fun slotsOf(type: IrType): Int = when {
        type is IrType.Primitive && (type.kind == TypeKind.LONG || type.kind == TypeKind.DOUBLE) -> 2
        else -> 1
    }
}
