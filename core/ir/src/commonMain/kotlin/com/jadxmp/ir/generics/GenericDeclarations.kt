package com.jadxmp.ir.generics

import com.jadxmp.ir.type.IrType

/** External source declarations, never executable program classes or candidates for code generation. */
data class GenericClassDeclaration(
    val name: String,
    val parameters: List<TypeParameter>,
    val superType: IrType?,
    val interfaces: List<IrType>,
    val methods: List<GenericMethodDeclaration>,
    val fields: List<GenericFieldDeclaration> = emptyList(),
    val accessFlags: Int = 0,
)

data class GenericMethodDeclaration(
    val name: String,
    val argumentTypes: List<IrType>,
    val returnType: IrType,
    val accessFlags: Int,
    val signature: MethodSignature? = null,
)

data class GenericFieldDeclaration(
    val name: String,
    val erasedType: IrType,
    val accessFlags: Int,
    val sourceType: IrType? = null,
)

/** Duplicate symbols must be resolved by the input/catalog owner, never silently by source lookup. */
class GenericDeclarationIndex(declarations: List<GenericClassDeclaration>) {
    private val classes = declarations.associateBy { it.name }
    init { require(classes.size == declarations.size) { "duplicate external generic class declaration" } }
    fun findClass(name: String): GenericClassDeclaration? = classes[name]
}
