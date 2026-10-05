package com.jadxmp.oracle

import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader

/** Subprocess entry point; never load fixture classes into the Gradle/oracle JVM. */
internal object CheckProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4)
        val marker = File(args[2])
        val urls = args[1].split(File.pathSeparatorChar).map { File(it).toURI().toURL() }.toTypedArray()
        // Only the JDK is inherited: an original class or a test dependency must not shadow a rebuild.
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            try {
                val cls = Class.forName(args[0], false, loader)
                fun findCheck(type: Class<*>) = type.methods.singleOrNull {
                    it.name == "check" && it.parameterCount == 0 &&
                        (it.returnType == Void.TYPE || it.returnType == java.lang.Boolean.TYPE)
                }
                var check = findCheck(cls)
                var instance: Any? = null
                if (check == null && args[3] == "true") {
                    val field = cls.fields.singleOrNull { it.name == "Companion" && Modifier.isStatic(it.modifiers) }
                    if (field != null) {
                        instance = field.get(null)
                        check = findCheck(field.type)
                    }
                }
                if (check == null) {
                    marker.writeText("missing")
                    return
                }
                if (instance == null && !Modifier.isStatic(check.modifiers)) instance = cls.getConstructor().newInstance()
                val result = check.invoke(instance)
                marker.writeText(if (check.returnType == Void.TYPE || result == true) "passed" else "failed")
            } catch (_: Throwable) {
                marker.writeText("failed")
            }
        }
    }
}
