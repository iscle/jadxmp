package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmConstantPoolTest {
    @Test fun resolvesForwardReferencesAndAllConstantTags() {
        val pool = read(ClassBytes().apply {
            u2(27)
            u1(7); u2(2) // 1: Class, forward UTF8
            utf("sample/Example") // 2
            utf("value") // 3
            utf("I") // 4
            u1(12); u2(3); u2(4) // 5: NameAndType
            u1(9); u2(1); u2(5) // 6: Fieldref
            u1(3); u4(0xFFFFFFFFL) // 7: Integer
            u1(4); u4(0x80000000L) // 8: Float -0
            u1(5); u8(Long.MIN_VALUE) // 9 + hole 10
            u1(6); u8(0x7FF8000000000001L) // 11 + hole 12: NaN payload
            u1(8); u2(3) // 13: String
            utf("call") // 14
            utf("()V") // 15
            u1(12); u2(14); u2(15) // 16
            u1(10); u2(1); u2(16) // 17: Methodref
            u1(11); u2(1); u2(16) // 18: InterfaceMethodref
            u1(15); u1(6); u2(17) // 19: MethodHandle
            u1(16); u2(15) // 20: MethodType
            u1(17); u2(0); u2(5) // 21: Dynamic
            u1(18); u2(1); u2(16) // 22: InvokeDynamic
            u1(19); u2(24) // 23: Module
            utf("sample.module") // 24
            u1(20); u2(26) // 25: Package
            utf("sample") // 26
        })
        assertEquals("sample/Example", pool.className(1))
        assertEquals(-1, (pool.entry(7) as JvmConstant.IntegerValue).value)
        assertEquals(Int.MIN_VALUE, (pool.entry(8) as JvmConstant.FloatBits).bits)
        assertEquals(Long.MIN_VALUE, (pool.entry(9) as JvmConstant.LongValue).value)
        assertEquals(0x7FF8000000000001L, (pool.entry(11) as JvmConstant.DoubleBits).bits)
        assertEquals("value", pool.utf8((pool.entry(13) as JvmConstant.StringRef).stringIndex))
        for (index in listOf(0, 10, 12, 27, -1)) {
            assertFailsWith<ByteReaderException> { pool.entry(index) }
        }
    }

    @Test fun rejectsUnknownTagsCyclesWrongKindsAndMissingWideSlots() {
        val cases = listOf(
            ClassBytes().apply { u2(0) },
            ClassBytes().apply { u2(2); u1(2) },
            ClassBytes().apply { u2(2); u1(7); u2(1) },
            ClassBytes().apply { u2(2); u1(7); u2(0) },
            ClassBytes().apply { u2(2); u1(5); u8(1) },
            ClassBytes().apply { u2(4); u1(5); u8(1); u1(7); u2(2) },
            ClassBytes().apply { u2(3); utf("not a descriptor"); u1(16); u2(1) },
        )
        for ((index, bytes) in cases.withIndex()) {
            assertFailsWith<ByteReaderException>("case $index") { read(bytes) }
        }
    }

    @Test fun checksMethodHandleKindsAndTargetDescriptors() {
        for (kind in 0..10) {
            val bytes = ClassBytes().apply {
                u2(8); utf("sample/Example"); u1(7); u2(1)
                utf("call"); utf("()V"); u1(12); u2(3); u2(4)
                u1(10); u2(2); u2(5)
                u1(15); u1(kind); u2(6)
            }
            if (kind in 5..7) read(bytes)
            else assertFailsWith<ByteReaderException>("kind $kind") { read(bytes) }
        }
    }

    @Test fun rejectsClassInitializerNameAndType() {
        assertFailsWith<ByteReaderException> {
            read(ClassBytes().apply {
                u2(4); utf("<clinit>"); utf("()V"); u1(12); u2(1); u2(2)
            })
        }
    }

    @Test fun handlesManyReferencesToSharedLongNamesAndDescriptors() {
        val name = "a".repeat(65520)
        val descriptor = "(L$name;)V"
        val pool = read(ClassBytes().apply {
            u2(40007)
            utf(name) // 1
            u1(7); u2(1) // 2
            utf("call") // 3
            utf(descriptor) // 4
            u1(12); u2(3); u2(4) // 5
            utf("()V") // 6
            repeat(10000) {
                u1(7); u2(1)
                u1(16); u2(4)
                u1(12); u2(3); u2(4)
                u1(10); u2(2); u2(5)
            }
        })
        assertEquals(name, pool.className(7))
        assertEquals(descriptor, pool.utf8(4))
    }

    @Test fun boundsPoolCountBeforeAllocation() {
        assertFailsWith<ByteReaderException> { read(ClassBytes().apply { u2(65535) }) }
    }

    private fun read(bytes: ClassBytes) = JvmConstantPool.read(ByteReader(bytes.bytes()), 61)
}
