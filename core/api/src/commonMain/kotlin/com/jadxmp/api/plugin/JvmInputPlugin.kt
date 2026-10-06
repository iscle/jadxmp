package com.jadxmp.api.plugin

import com.jadxmp.input.CodeLoader
import com.jadxmp.input.jvm.JvmInput

/** Native single-class input. Unsupported method bodies retain their input diagnostics. */
object JvmInputPlugin : InputPlugin {
    override val id: String = "jvm-class"

    override fun tryLoad(name: String, bytes: ByteArray): CodeLoader? {
        // Recognize content, not extensions: a misnamed class is still a class; a text file
        // ending in .class is not. Once recognized, parser failures propagate to the facade.
        if (bytes.size < 4 || bytes[0] != 0xca.toByte() || bytes[1] != 0xfe.toByte() ||
            bytes[2] != 0xba.toByte() || bytes[3] != 0xbe.toByte()) return null
        return JvmInput.loadClass(name, bytes)
    }
}
