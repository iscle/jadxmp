package com.jadxmp.codegen

import com.jadxmp.ir.node.*
import com.jadxmp.ir.type.IrType
import kotlin.test.*

class AnnotationDeclarationShapeTest {
    @Test fun onlyTheExactImplicitAnnotationSuperinterfaceIsSourceRepresentable() {
        val annotation = IrType.objectType("java.lang.annotation.Annotation")
        for (interfaces in listOf(emptyList(), listOf(IrType.objectType("Marker")),
            listOf(annotation, IrType.objectType("Marker")), listOf(annotation, annotation), listOf(annotation))) {
            val root = IrRoot()
            val owner = IrClass(root, "Tag", 0x2601, IrType.OBJECT, interfaces).also(root::addClass)
            val problems = AnnotationEmissionPlan(root).node(owner).problems
            if (interfaces == listOf(annotation)) assertTrue(problems.isEmpty(), problems.toString())
            else assertTrue(problems.any { "annotation superinterfaces" in it }, problems.toString())
        }
    }
    @Test fun instanceBodiesOrNonElementFlagsMustNotBecomeAbstractProperties() {
        for (flags in listOf(1, 2, 0x501, 0x1401, 0x401)) {
            val root = IrRoot()
            val owner = IrClass(root, "Tag", 0x2601, IrType.OBJECT,
                listOf(IrType.objectType("java.lang.annotation.Annotation"))).also(root::addClass)
            owner.methods.add(IrMethod(owner, "value", IrType.INT, emptyList(), flags))
            val problems = AnnotationEmissionPlan(root).node(owner).problems
            if (flags == 0x401) assertTrue(problems.isEmpty(), problems.toString())
            else assertTrue(problems.any { "annotation instance method" in it }, problems.toString())
        }
    }

    @Test fun abstractFlagsDoNotHideEncodedInstructions() {
        val root = IrRoot()
        val owner = IrClass(root, "Tag", 0x2601, IrType.OBJECT,
            listOf(IrType.objectType("java.lang.annotation.Annotation"))).also(root::addClass)
        val method = IrMethod(owner, "value", IrType.INT, emptyList(), 0x401).also(owner.methods::add)
        method.blocks.add(BasicBlock(0).also {
            it.instructions.add(com.jadxmp.ir.insn.Instruction(com.jadxmp.ir.insn.IrOpcode.NOP))
        })
        assertTrue(AnnotationEmissionPlan(root).node(owner).problems.any { "annotation instance method" in it })
    }

}
