package com.jadxmp.oracle

import com.jadxmp.codegen.FloatingConstantLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Actual annotation defaults and uses reject runtime fromBits helpers and preserve raw JVM bits. */
class FloatingAnnotationConstantSemanticsTest {
    @Test fun javaAndPinnedKotlinCompileExactAnnotationDefaultsAndExplicitValues() {
        val floats = mutableListOf(0, Int.MIN_VALUE, 1, Int.MIN_VALUE or 1, 0x007fffff, 0x00800000,
            0x00800001, 0x3dcccccd, 0x3f7fffff, 0x3f800000, 0x3f800001, 0x7f7fffff,
            -0x00800001, 0x7f800000, -0x00800000, 0x7fc00000)
        val doubles = mutableListOf(0L, Long.MIN_VALUE, 1L, Long.MIN_VALUE or 1, 0x000fffffffffffffL,
            0x0010000000000000L, 0x0010000000000001L, 0x3fb999999999999aL,
            0x3fefffffffffffffL, 0x3ff0000000000000L, 0x3ff0000000000001L,
            0x7fefffffffffffffL, -0x0010000000000001L, 0x7ff0000000000000L,
            -0x0010000000000000L, 0x7ff8000000000000L)
        var seed = 987654321L
        repeat(64) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            floats += seed.toInt() and -0x00800001
            doubles += seed and -0x0010000000000001L
        }
        for (kotlin in listOf(false, true)) {
            val members = floats.mapIndexed { i, bits -> Triple("f$i", "Float", FloatingConstantLiteral.float(bits)!!) } +
                doubles.mapIndexed { i, bits -> Triple("d$i", "Double", FloatingConstantLiteral.double(bits)!!) }
            val annotation = DecompiledClass("constants.Values", if (kotlin) """
                package constants
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Values(
                    ${members.joinToString(",\n") { (name, type, source) -> "val $name: $type = $source" }}
                )
            """.trimIndent() else """
                package constants;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Values {
                    ${members.joinToString("\n") { (name, type, source) -> "${type.lowercase()} $name() default $source;" }}
                }
            """.trimIndent())
            val arguments = members.joinToString(", ") { (name, _, source) -> "$name = $source" }
            val explicit = DecompiledClass("constants.Explicit", if (kotlin)
                "package constants\n@Values($arguments) class Explicit" else
                "package constants; @Values($arguments) public class Explicit {}")
            val defaults = DecompiledClass("constants.Defaults", if (kotlin)
                "package constants\n@Values class Defaults" else
                "package constants; @Values public class Defaults {}")
            withCompiledClasses(listOf(annotation, explicit, defaults), kotlin) { loader ->
                val type = loader.loadClass(annotation.fullName)
                for (owner in listOf(explicit.fullName, defaults.fullName)) {
                    val value = loader.loadClass(owner).declaredAnnotations.single { it.annotationClass.java == type }
                    for ((index, bits) in floats.withIndex()) {
                        val method = type.getMethod("f$index")
                        assertEquals(bits, (method.defaultValue as Float).toRawBits(), "$kotlin default f$index")
                        assertEquals(bits, (method.invoke(value) as Float).toRawBits(), "$kotlin $owner f$index")
                    }
                    for ((index, bits) in doubles.withIndex()) {
                        val method = type.getMethod("d$index")
                        assertEquals(bits, (method.defaultValue as Double).toRawBits(), "$kotlin default d$index")
                        assertEquals(bits, (method.invoke(value) as Double).toRawBits(), "$kotlin $owner d$index")
                    }
                }
            }
        }
    }
}
