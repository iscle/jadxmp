package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinOverloadBindingTest {
    @Test fun loadedOverloadMustUseTheBytecodeParameterType() {
        val cls = irClass("a.Foo")
        for (type in listOf(IrType.OBJECT, IrType.STRING)) {
            cls.method("use", argTypes = listOf(type), accessFlags = Flags.PUBLIC or Flags.STATIC) { body(com.jadxmp.ir.insn.Instruction(com.jadxmp.ir.insn.IrOpcode.RETURN)) }
        }
        val value = Local(0, IrType.STRING, "value", isParam = true)
        cls.method("test", argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(staticInvoke(IrType.objectType("a.Foo"), "use", IrType.VOID, listOf(IrType.OBJECT), listOf(value.ref())))
        }
        assertThatCode(generate(cls)).containsOne("Foo.use(value as Any?)")
    }
    @Test fun oneOutputSharesTheBindingBudgetAcrossMethodBodies() {
        val cls = irClass("example.ManyCalls")
        val ownerName = "p".repeat(30_000) + ".Owner"
        val owner = com.jadxmp.ir.node.IrClass(cls.root, ownerName, Flags.PUBLIC)
        cls.root.addClass(owner)
        for (type in listOf(IrType.OBJECT, IrType.STRING)) {
            owner.method("use", argTypes = listOf(type), accessFlags = Flags.PUBLIC or Flags.STATIC) {
                body(com.jadxmp.ir.insn.Instruction(com.jadxmp.ir.insn.IrOpcode.RETURN))
            }
        }
        // Each discovery/emission pass fits separately; the whole output exceeds the budget.
        repeat(100) { index ->
            val value = Local(0, IrType.STRING, "value", isParam = true)
            cls.method("test$index", argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC) {
                this[CodegenKeys.PARAM_NAMES] = listOf("value")
                body(staticInvoke(IrType.objectType(ownerName), "use", IrType.VOID, listOf(IrType.OBJECT), listOf(value.ref())))
            }
        }
        cls.method("healthy", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) { body(ret(lit(7, IrType.INT))) }
        val output = generate(cls)
        kotlin.test.assertTrue(output.contains("JADXMP ERROR:"))
        kotlin.test.assertTrue(cls.methods.any { it.contains(com.jadxmp.ir.attr.AttrFlag.HAS_ERROR) })
        assertThatCode(output).containsOne("return 7")
    }

    @Test fun overloadBudgetFailureInFieldInitializerKeepsHealthyMembersAndResidualInit() {
        val cls = irClass("example.ManyFields")
        val ownerName = "p".repeat(30_000) + ".Owner"
        val owner = irClass(ownerName, root = cls.root)
        for (parameter in listOf(IrType.OBJECT, IrType.STRING)) {
            owner.method("use", returnType = IrType.STRING, argTypes = listOf(parameter), accessFlags = Flags.PUBLIC or Flags.STATIC)
        }
        var value: com.jadxmp.ir.insn.Operand = expr(constString("value"))
        repeat(200) { index ->
            value = expr(staticInvoke(IrType.objectType(ownerName), "use", IrType.STRING, listOf(IrType.OBJECT), listOf(value), reg(index, IrType.STRING)))
        }
        cls.fields.add(com.jadxmp.ir.node.IrField(cls, "field", IrType.STRING, Flags.PUBLIC or Flags.STATIC or Flags.FINAL))
        val store = com.jadxmp.ir.insn.FieldInstruction(com.jadxmp.ir.insn.FieldRef(IrType.objectType(cls.fullName), "field", IrType.STRING),
            isStatic = true, isPut = true, args = listOf(value))
        val clinit = cls.method("<clinit>", accessFlags = Flags.STATIC) { body(store, ret()) }
        cls.method("healthy", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) { body(ret(lit(7, IrType.INT))) }
        val output = generate(cls)
        kotlin.test.assertTrue(output.contains("JADXMP ERROR:"))
        kotlin.test.assertTrue(cls.fields.any { it.contains(com.jadxmp.ir.attr.AttrFlag.HAS_ERROR) })
        kotlin.test.assertTrue(clinit.contains(com.jadxmp.ir.attr.AttrFlag.HAS_ERROR), "failed hoists must leave residual initialization visible")
        assertThatCode(output).containsOne("return 7")
    }

}
