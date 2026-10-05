package com.jadxmp.oracle

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.kotlin.KotlinCodeGenerator
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

class KotlinProjectedIndexSemanticsTest {
    @Test
    fun indexEvaluatesBeforeNullReceiverFailureAndTemporariesDoNotCaptureOperands() {
        val helper = DecompiledClass("testing.IndexProbe", """
            package testing;
            public class IndexProbe {
                public static int count;
                public static int value;
                public static boolean fail;
                public static int index(int input) {
                    count++;
                    value = input;
                    if (fail) throw new IllegalStateException("index failed");
                    return input;
                }
            }
        """.trimIndent())
        val root = IrRoot()
        val cls = IrClass(root, "IndexOrder", 1)
        root.addClass(cls)
        val method = IrMethod(cls, "test", IrType.CHAR, listOf(IrType.INT, IrType.INT), 1 or 8)
        cls.methods.add(method)
        method[CodegenKeys.PARAM_NAMES] = listOf("receiver", "index")
        val sum = ArithInstruction(ArithOp.ADD, RegisterOperand(-1, IrType.INT), listOf(parameter(0, "receiver"), parameter(1, "index")))
        val index = InvokeInstruction(
            MethodRef(IrType.objectType(helper.fullName), "index", IrType.INT, listOf(IrType.INT)),
            InvokeKind.STATIC,
            RegisterOperand(-1, IrType.INT),
            listOf(InstructionOperand(sum)),
        )
        val call = InvokeInstruction(
            MethodRef(IrType.objectType("java.lang.CharSequence"), "charAt", IrType.CHAR, listOf(IrType.INT)),
            InvokeKind.INTERFACE,
            RegisterOperand(-1, IrType.CHAR),
            listOf(LiteralOperand(0, IrType.objectType("testing.ConcreteSequence")), InstructionOperand(index)),
        )
        method.blocks.add(BasicBlock(0).apply { instructions.add(Instruction(IrOpcode.RETURN, args = listOf(InstructionOperand(call)))) })
        val source = DecompiledClass(cls.fullName, KotlinCodeGenerator().generate(cls).code)
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(source, kotlin = true, additionalClasspath = listOf(dependency.output)) { generated ->
                val target = generated.getField("Companion").get(null)
                val probe = generated.classLoader.loadClass(helper.fullName)
                val test = target.javaClass.getMethod("test", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                for (fail in listOf(false, true)) {
                    probe.getField("count").setInt(null, 0)
                    probe.getField("fail").setBoolean(null, fail)
                    val thrown = runCatching { test.invoke(target, 2, 3) }.exceptionOrNull()
                    assertTrue(thrown is InvocationTargetException, source.source)
                    val cause = (thrown as InvocationTargetException).cause
                    assertTrue(if (fail) cause is IllegalStateException else cause is NullPointerException, "$cause\n${source.source}")
                    assertEquals(1, probe.getField("count").getInt(null), "argument must be evaluated once before the receiver null check")
                    assertEquals(5, probe.getField("value").getInt(null), "synthetic receiver/index names must not capture the parameters")
                }
            }
        }
    }

    private fun parameter(register: Int, name: String): RegisterOperand {
        val local = LocalVar().apply {
            this.name = name
            type = IrType.INT
            add(AttrFlag.METHOD_ARGUMENT)
        }
        val value = SsaValue(register, 0, RegisterOperand(register, IrType.INT)).apply {
            add(AttrFlag.METHOD_ARGUMENT)
            local.addSsaValue(this)
        }
        return RegisterOperand(register, IrType.INT).also { it.ssaValue = value }
    }
}
