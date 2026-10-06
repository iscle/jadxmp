package com.jadxmp.ir.generics

import com.jadxmp.ir.attr.AttrKey
import com.jadxmp.ir.type.IrType

/** Source-only generic declarations. Erased node/member descriptors remain authoritative for dispatch. */
data class TypeParameter(
    val name: String,
    val classBound: IrType?,
    val interfaceBounds: List<IrType>,
    /** Established in this declaration's lexical scope; later same-named parameters cannot rebind it. */
    val erasedType: IrType? = null,
) {
    val bounds: List<IrType> get() = listOfNotNull(classBound) + interfaceBounds
}

data class ClassSignature(val parameters: List<TypeParameter>, val superType: IrType, val interfaces: List<IrType>)
data class MethodSignature(
    val parameters: List<TypeParameter>,
    val argumentTypes: List<IrType>,
    val returnType: IrType,
    val throwsTypes: List<IrType>,
    /** Verified implicit enum/enclosing-instance constructor parameters omitted from the source signature. */
    val erasedPrefixTypes: List<IrType> = emptyList(),
)

/** A possible omitted constructor prefix; not validated until executable capture/super-call evidence is checked. */
data class PendingConstructorSignature(val signature: MethodSignature, val prefixTypes: List<IrType>)

/** Visible recovery of invalid optional metadata; original reflective metadata is deliberately not recreated. */
data class GenericSignatureRecovery(val signature: String?, val reason: String)

/** Only scope- and erasure-validated declarations may be attached to these keys. */
object GenericAttributes {
    /** On IrRoot: library metadata only, kept separate from generated program definitions. */
    val EXTERNAL_DECLARATIONS: AttrKey<GenericDeclarationIndex> = AttrKey("generic.externalDeclarations")
    val RAW_SIGNATURE: AttrKey<String> = AttrKey("generic.rawSignature")
    val RECOVERIES: AttrKey<List<GenericSignatureRecovery>> = AttrKey("generic.recoveries")
    val CLASS: AttrKey<ClassSignature> = AttrKey("generic.class")
    val PENDING_CONSTRUCTOR: AttrKey<PendingConstructorSignature> = AttrKey("generic.pendingConstructor")
    val METHOD: AttrKey<MethodSignature> = AttrKey("generic.method")
    val FIELD: AttrKey<IrType> = AttrKey("generic.field")
}
