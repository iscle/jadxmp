package com.jadxmp.input.jvm

import com.jadxmp.input.CodeLoader
import com.jadxmp.input.ListCodeLoader

/** Native single-class input, also exposed by the default facade plugin. Archive loading remains separate. */
public object JvmInput {
    public fun loadClass(name: String, bytes: ByteArray): CodeLoader =
        ListCodeLoader(listOf(JvmClassAdapter.adapt(ClassFileParser.parse(bytes), name)))
}
