package com.jadxmp.input.jvm

import com.jadxmp.input.CodeLoader
import com.jadxmp.input.ListCodeLoader

/** Native single-class input. Archive loading and default facade registration remain separate. */
public object JvmInput {
    public fun loadClass(name: String, bytes: ByteArray): CodeLoader =
        ListCodeLoader(listOf(JvmClassAdapter.adapt(ClassFileParser.parse(bytes), name)))
}
