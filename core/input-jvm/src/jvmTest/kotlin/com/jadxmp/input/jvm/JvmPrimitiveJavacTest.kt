package com.jadxmp.input.jvm

import com.jadxmp.input.CodeReader
import com.jadxmp.input.Opcode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmPrimitiveJavacTest {
    @Test fun nativeIreturnNarrowsEvenWhenBytecodeDoesNotContainExplicitCast() {
        val names = listOf("bool", "octet", "character", "shortValue")
        val descriptors = listOf("Z", "B", "C", "S")
        val bytes = rawClass(names.indices.map { index ->
            RawMethod(names[index], "(I)${descriptors[index]}", byteArrayOf(0x1a, 0xac.toByte()), 1, 1)
        })
        val klass = object : ClassLoader() {
            fun load(): Class<*> = defineClass(null, bytes, 0, bytes.size)
        }.load()
        val file = ClassFileParser.parse(bytes)
        for (member in file.methods) {
            val descriptor = JvmMethodDescriptor.parse(member.descriptor)
            val code = JvmCodeAttribute.parse(member.attributes.single(), file.constants)
            val reader = JvmPrimitiveNormalizer.normalize(file.name, member, descriptor, file.constants, code)
            val method = klass.getDeclaredMethod(member.name, Int::class.javaPrimitiveType)
            for (value in listOf(-65537, -257, -2, -1, 0, 1, 2, 255, 256, 65535, 65536)) {
                assertEquals(bits(method.invoke(null, value)), execute(reader, descriptor, listOf(value), false),
                    "${member.name}($value)")
            }
        }
    }

    @Test fun wideStoresMayCrossOriginalIncomingParameterBoundary() {
        val methods = listOf(
            RawMethod("head", "(I)J", byteArrayOf(0x0a, 0x3f, 0x1e, 0xad.toByte()), 2, 2),
            RawMethod("tail", "(II)J", byteArrayOf(0x0a, 0x40, 0x1f, 0xad.toByte()), 3, 2),
        )
        val bytes = rawClass(methods)
        val klass = object : ClassLoader() {
            fun load(): Class<*> = defineClass(null, bytes, 0, bytes.size)
        }.load()
        val file = ClassFileParser.parse(bytes)
        for (member in file.methods) {
            val descriptor = JvmMethodDescriptor.parse(member.descriptor)
            val code = JvmCodeAttribute.parse(member.attributes.single(), file.constants)
            val reader = JvmPrimitiveNormalizer.normalize(file.name, member, descriptor, file.constants, code)
            val arguments = List(descriptor.parameterTypes.size) { 123 }
            val method = klass.getDeclaredMethod(member.name, *Array(arguments.size) { Int::class.javaPrimitiveType })
            reader.visitInstructions { instruction ->
                instruction.decode()
                if (instruction.opcode == Opcode.MOVE_WIDE) {
                    for (operand in 0 until instruction.registerCount) {
                        kotlin.test.assertTrue(instruction.register(operand) + 1 < reader.registerCount,
                            "wide operand exceeds frame: ${instruction.register(operand)}/${reader.registerCount}")
                    }
                }
            }
            assertEquals(bits(method.invoke(null, *arguments.toTypedArray())),
                execute(reader, descriptor, arguments, false), member.name)
        }
    }

    private data class RawMethod(val name: String, val descriptor: String, val bytes: ByteArray,
        val locals: Int, val stack: Int)

    private fun rawClass(methods: List<RawMethod>): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { out ->
            out.writeInt(0xcafebabe.toInt())
            out.writeShort(0)
            out.writeShort(49)
            out.writeShort(6 + methods.size * 2)
            fun utf8(value: String) { out.writeByte(1); out.writeUTF(value) }
            utf8("RawPrimitives")
            out.writeByte(7); out.writeShort(1)
            utf8("java/lang/Object")
            out.writeByte(7); out.writeShort(3)
            utf8("Code")
            for (method in methods) { utf8(method.name); utf8(method.descriptor) }
            out.writeShort(0x21)
            out.writeShort(2)
            out.writeShort(4)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(methods.size)
            for ((index, method) in methods.withIndex()) {
                out.writeShort(9)
                out.writeShort(6 + index * 2)
                out.writeShort(7 + index * 2)
                out.writeShort(1)
                out.writeShort(5)
                out.writeInt(12 + method.bytes.size)
                out.writeShort(method.stack)
                out.writeShort(method.locals)
                out.writeInt(method.bytes.size)
                out.write(method.bytes)
                out.writeShort(0)
                out.writeShort(0)
            }
            out.writeShort(0)
        }
    }.toByteArray()

    @Test fun nativeNormalizationMatchesJavacExecutionWithoutDexConversion() {
        val directory = Files.createTempDirectory("jadxmp-jvm-primitives").toFile()
        try {
            val source = directory.resolve("Primitives.java").apply {
                writeText("""
                    public class Primitives {
                        public static int snapshot(int value) { return value++; }
                        public static long mixed(int a, long b, int c) { return b + a - c; }
                        public long instance(int a, long b) { return b + a; }
                        public static int arithmetic(int a, int b) { return (a - b) * (a + b); }
                        public static int extrema(int value) { return (value * 31) ^ (value >>> 3); }
                        public static long narrow(long value) { int local = (int) value; return local; }
                        public static double zero() { return -0.0; }
                        public static float nan() { return Float.NaN; }
                        public static float negate(float value) { return -value; }
                        public static double divide(double left, double right) { return left / right; }
                    }
                """.trimIndent())
            }
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-g", "-d", directory.path, source.path))
            val file = ClassFileParser.parse(directory.resolve("Primitives.class").readBytes())
            URLClassLoader(arrayOf(directory.toURI().toURL())).use { loader ->
                val klass = loader.loadClass("Primitives")
                val instance = klass.getDeclaredConstructor().newInstance()
                val cases = listOf(
                    Case("snapshot", listOf(0)), Case("snapshot", listOf(Int.MAX_VALUE)),
                    Case("snapshot", listOf(Int.MIN_VALUE)),
                    Case("mixed", listOf<Any>(Int.MAX_VALUE, Long.MAX_VALUE, Int.MIN_VALUE)),
                    Case("mixed", listOf<Any>(-7, Long.MIN_VALUE, 9)),
                    Case("instance", listOf<Any>(-7, Long.MIN_VALUE), instance),
                    Case("arithmetic", listOf(Int.MAX_VALUE, -33)), Case("extrema", listOf(Int.MIN_VALUE)),
                    Case("narrow", listOf(Long.MAX_VALUE)), Case("narrow", listOf(0x1234567887654321L)),
                    Case("zero", emptyList()), Case("nan", emptyList()),
                    Case("negate", listOf(0.0f)), Case("negate", listOf(-0.0f)), Case("negate", listOf(Float.NaN)),
                    Case("divide", listOf(1.0, -0.0)), Case("divide", listOf(0.0, 0.0)),
                )
                for (case in cases) {
                    val member = file.methods.single { it.name == case.name }
                    val descriptor = JvmMethodDescriptor.parse(member.descriptor)
                    val code = JvmCodeAttribute.parse(member.attributes.single { it.name == "Code" }, file.constants)
                    val reader = JvmPrimitiveNormalizer.normalize(file.name, member, descriptor, file.constants, code)
                    val method = klass.getDeclaredMethod(case.name, *case.arguments.map(::primitiveClass).toTypedArray())
                    val expected = method.invoke(case.receiver, *case.arguments.toTypedArray())
                    val actual = execute(reader, descriptor, case.arguments, case.receiver != null)
                    assertEquals(bits(expected), actual, "${case.name}${case.arguments}")
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Small test-only register interpreter, independent of JVM stack decoding and register mapping. */
    private fun execute(reader: CodeReader, descriptor: JvmMethodDescriptor, arguments: List<Any>, instance: Boolean): Long {
        val registers = LongArray(reader.registerCount)
        var parameter = reader.registerCount - descriptor.argumentSlots
        if (instance) registers[parameter - 1] = 1 // Receiver is unused by the primitive-only fixture.
        for ((index, argument) in arguments.withIndex()) {
            registers[parameter] = bits(argument)
            parameter += if (descriptor.parameterTypes[index] in listOf("J", "D")) 2 else 1
        }
        var result: Long? = null
        reader.visitInstructions { instruction ->
            instruction.decode()
            fun input(index: Int) = registers[instruction.register(index)]
            val value = when (instruction.opcode) {
                Opcode.CONST, Opcode.CONST_WIDE -> instruction.literal
                Opcode.MOVE, Opcode.MOVE_WIDE, Opcode.MOVE_OBJECT -> input(1)
                Opcode.ADD_INT_LIT -> (input(1).toInt() + instruction.literal.toInt()).toLong()
                Opcode.AND_INT_LIT -> (input(1).toInt() and instruction.literal.toInt()).toLong()
                Opcode.INT_TO_BYTE -> input(1).toByte().toLong()
                Opcode.INT_TO_CHAR -> input(1).toInt().toChar().code.toLong()
                Opcode.INT_TO_SHORT -> input(1).toShort().toLong()
                Opcode.ADD_INT -> (input(1).toInt() + input(2).toInt()).toLong()
                Opcode.SUB_INT -> (input(1).toInt() - input(2).toInt()).toLong()
                Opcode.MUL_INT -> (input(1).toInt() * input(2).toInt()).toLong()
                Opcode.XOR_INT -> (input(1).toInt() xor input(2).toInt()).toLong()
                Opcode.USHR_INT -> (input(1).toInt() ushr input(2).toInt()).toLong()
                Opcode.ADD_LONG -> input(1) + input(2)
                Opcode.SUB_LONG -> input(1) - input(2)
                Opcode.INT_TO_LONG -> input(1).toInt().toLong()
                Opcode.LONG_TO_INT -> input(1).toInt().toLong()
                Opcode.NEG_FLOAT -> (-Float.fromBits(input(1).toInt())).toRawBits().toLong()
                Opcode.DIV_DOUBLE -> (Double.fromBits(input(1)) / Double.fromBits(input(2))).toRawBits()
                Opcode.RETURN -> {
                    result = input(0)
                    null
                }
                else -> error("test interpreter does not implement ${instruction.opcode}")
            }
            if (value != null) registers[instruction.register(0)] = value
        }
        val raw = checkNotNull(result)
        // NaN payloads from arithmetic are VM-dependent; compare canonical NaN while retaining -0.
        return when (descriptor.returnType) {
            "F" -> Float.fromBits(raw.toInt()).toBits().toLong()
            "D" -> Double.fromBits(raw).toBits()
            else -> raw
        }
    }

    private fun bits(value: Any?): Long = when (value) {
        is Boolean -> if (value) 1L else 0L
        is Byte -> value.toLong()
        is Char -> value.code.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        is Float -> value.toBits().toLong()
        is Double -> value.toBits()
        else -> error("unexpected primitive result $value")
    }
    private fun primitiveClass(value: Any): Class<*> = when (value) {
        is Int -> Int::class.javaPrimitiveType!!
        is Long -> Long::class.javaPrimitiveType!!
        is Float -> Float::class.javaPrimitiveType!!
        is Double -> Double::class.javaPrimitiveType!!
        else -> error("unexpected primitive argument $value")
    }
    private data class Case(val name: String, val arguments: List<Any>, val receiver: Any? = null)
}
