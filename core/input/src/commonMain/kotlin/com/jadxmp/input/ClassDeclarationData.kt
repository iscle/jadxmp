package com.jadxmp.input

/**
 * Classpath source declarations only. These cannot be loaded as executable program [ClassData].
 * Descriptors remain authoritative; optional generic signatures are validated by the model layer.
 * Annotations, bodies and constant initializers are intentionally outside this metadata contract.
 */
public data class ClassDeclarationData(
    public val type: String,
    public val accessFlags: Int,
    public val superType: String?,
    public val interfaces: List<String>,
    public val fields: List<FieldDeclarationData>,
    public val methods: List<MethodDeclarationData>,
    public val genericSignature: String? = null,
    public val nesting: ClassNesting? = null,
    public val innerAccessFlags: Int? = null,
)

public data class FieldDeclarationData(
    public val name: String,
    public val type: String,
    public val accessFlags: Int,
    public val genericSignature: String? = null,
)

public data class MethodDeclarationData(
    public val name: String,
    public val parameterTypes: List<String>,
    public val returnType: String,
    public val accessFlags: Int,
    public val genericSignature: String? = null,
)
