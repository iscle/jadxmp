package com.jadxmp.input.jvm

/** Parser-private structure, separate from the normalized register-based input SPI. */
internal data class ClassFile(
    val minorVersion: Int,
    val majorVersion: Int,
    val constants: JvmConstantPool,
    val accessFlags: Int,
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val fields: List<JvmMember>,
    val methods: List<JvmMember>,
    val attributes: List<JvmAttribute>,
)

internal data class JvmMember(
    val accessFlags: Int,
    val name: String,
    val descriptor: String,
    val attributes: List<JvmAttribute>,
)

/** Unknown attributes are retained byte-for-byte; each later decoder gets an independent boundary. */
internal class JvmAttribute(val name: String, val offset: Int, val bytes: ByteArray)
