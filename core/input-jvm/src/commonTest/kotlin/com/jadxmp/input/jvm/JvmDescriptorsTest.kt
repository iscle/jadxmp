package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JvmDescriptorsTest {
    @Test fun validatesFieldTypesWithoutRecursiveArrayParsing() {
        for (value in listOf("Z", "B", "C", "S", "I", "J", "F", "D", "Ljava/lang/String;", "[".repeat(255) + "I")) {
            JvmDescriptors.field(value)
        }
        for (value in listOf("", "V", "[V", "L;", "Ljava.lang.String;", "L/foo;", "Lfoo//bar;", "II", "[".repeat(256) + "I")) {
            assertFailsWith<ByteReaderException>(value) { JvmDescriptors.field(value) }
        }
    }

    @Test fun validatesMethodParametersAndCategoryTwoSlotLimit() {
        for (value in listOf("()V", "(J[D[[Ljava/lang/String;)Ljava/lang/Object;", "(" + "I".repeat(255) + ")V")) {
            JvmDescriptors.method(value)
        }
        for (value in listOf("", "I", "()", "(V)V", "(I", "()VI", "(" + "J".repeat(128) + ")V")) {
            assertFailsWith<ByteReaderException>(value) { JvmDescriptors.method(value) }
        }
    }
}
