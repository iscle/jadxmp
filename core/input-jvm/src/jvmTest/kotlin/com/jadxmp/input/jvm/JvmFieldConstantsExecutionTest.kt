package com.jadxmp.input.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmFieldConstantsExecutionTest {
    @Test fun constantsMatchActualJvmInitializationIncludingNarrowingAndIgnoredInstanceValues() {
        val cases: List<Pair<String, ClassBytes.() -> Unit>> = listOf(
            "Z" to { u1(3); u4(2) }, "Z" to { u1(3); u4(-1) },
            "B" to { u1(3); u4(255) }, "S" to { u1(3); u4(65535) },
            "C" to { u1(3); u4(-1) }, "I" to { u1(3); u4(Int.MIN_VALUE.toLong()) },
            "J" to { u1(5); u8(Long.MIN_VALUE) },
            "F" to { u1(4); u4(0x80000000L) }, "D" to { u1(6); u8(Long.MIN_VALUE) },
            "Ljava/lang/String;" to { u1(8); u2(14); utf("constant text") },
        )
        for ((descriptor, constant) in cases) {
            // FINAL is deliberately absent: ConstantValue initializes every static field.
            val bytes = sample(descriptor, 0x0009, constant)
            val loaded = load(bytes)
            val file = ClassFileParser.parse(bytes)
            val decoded = JvmFieldConstants.read(file.fields.single(), file.constants)!!
            val actual = loaded.getField("value").get(null)
            assertEquals(actual, decoded.value, descriptor)
            if (actual is Float) assertEquals(actual.toRawBits(), (decoded.value as Float).toRawBits())
            if (actual is Double) assertEquals(actual.toRawBits(), (decoded.value as Double).toRawBits())
        }
        val bytes = sample("I", 0x0011) { u1(3); u4(99) }
        val loaded = load(bytes)
        assertEquals(0, loaded.getField("value").get(loaded.getConstructor().newInstance()))
        val file = ClassFileParser.parse(bytes)
        assertNull(JvmFieldConstants.read(file.fields.single(), file.constants))
    }

    private fun load(bytes: ByteArray): Class<*> = object : ClassLoader() {
        fun define(): Class<*> = defineClass(null, bytes, 0, bytes.size)
    }.define()

    private fun sample(descriptor: String, flags: Int, constant: ClassBytes.() -> Unit): ByteArray = ClassBytes().apply {
        val extraSlot = descriptor in listOf("J", "D", "Ljava/lang/String;")
        u4(0xcafebabeL); u2(0); u2(61); u2(if (extraSlot) 15 else 14)
        utf("ConstantFields"); u1(7); u2(1) // 1,2
        utf("java/lang/Object"); u1(7); u2(3) // 3,4
        utf("<init>"); utf("()V"); u1(12); u2(5); u2(6) // 5,6,7
        u1(10); u2(4); u2(7); utf("Code"); utf("ConstantValue") // 8,9,10
        utf("value"); utf(descriptor); constant() // 11,12,13 (+14 for string or wide hole)
        u2(0x21); u2(2); u2(4); u2(0)
        u2(1); u2(flags); u2(11); u2(12); u2(1); u2(10); u4(2); u2(13)
        u2(1); u2(1); u2(5); u2(6); u2(1); u2(9); u4(17)
        u2(1); u2(1); u4(5); u1(42); u1(183); u2(8); u1(177); u2(0); u2(0)
        u2(0)
    }.bytes()
}
