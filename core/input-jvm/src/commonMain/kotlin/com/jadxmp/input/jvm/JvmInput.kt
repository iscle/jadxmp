package com.jadxmp.input.jvm

import com.jadxmp.input.ClassDeclarationData
import com.jadxmp.input.CodeLoader
import com.jadxmp.input.ListCodeLoader

/** Native single-class input, also exposed by the default facade plugin. Archive loading remains separate. */
public object JvmInput {
    /** Classpath type metadata only; annotations and executable bodies are not imported. */
    public fun readDeclaration(bytes: ByteArray): ClassDeclarationData =
        JvmDeclarationReader.read(ClassFileParser.parse(bytes))

    public fun loadClass(name: String, bytes: ByteArray): CodeLoader =
        ListCodeLoader(listOf(JvmClassAdapter.adapt(ClassFileParser.parse(bytes), name)))
}
