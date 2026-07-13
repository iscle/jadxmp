package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.DefinitionAnnotation
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.RefKind
import com.jadxmp.codegen.ReferenceAnnotation
import com.jadxmp.codegen.VariableAnnotation
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinMetadataTest {

    private fun sample() = run {
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.INT)
        cls.method("m", returnType = IrType.INT) {
            body(
                assign(
                    r.ref(),
                    staticInvoke(
                        IrType.objectType("java.lang.Integer"),
                        "parseInt",
                        IrType.INT,
                        listOf(IrType.STRING),
                        listOf(expr(constString("5"))),
                    ),
                ),
                ret(r.ref()),
            )
        }
        KotlinCodeGenerator().generate(cls)
    }

    @Test
    fun classDefinitionLandsOnClassName() {
        val info = sample()
        val offset = info.code.indexOf("class Foo") + "class ".length
        val ann = info.metadata.at(offset)
        assertTrue(ann is DefinitionAnnotation, "expected a definition at the class name, got $ann")
        assertEquals(ClassNodeRef("a.Foo"), ann.ref)
    }

    @Test
    fun methodReferenceLandsOnInvokedName() {
        val info = sample()
        val offset = info.code.indexOf("parseInt")
        val ann = info.metadata.at(offset)
        assertTrue(ann is ReferenceAnnotation, "expected a reference at the invoke, got $ann")
        val ref = ann.ref
        assertTrue(ref is MethodNodeRef && ref.name == "parseInt" && ref.ownerClass == "java.lang.Integer")
    }

    @Test
    fun variableDeclarationIsMarked() {
        val info = sample()
        // "var i: Int = ..." — the variable 'i' sits one token after "var ".
        val offset = info.code.indexOf("var i") + "var ".length
        val ann = info.metadata.at(offset)
        assertTrue(ann is VariableAnnotation && ann.declaration && ann.ref.name == "i", "got $ann")
    }

    @Test
    fun nodeAtResolvesEnclosingMethodThenClass() {
        val info = sample()
        val insideBody = info.code.indexOf("parseInt")
        val enclosing = info.metadata.nodeAt(insideBody)
        assertTrue(enclosing is MethodNodeRef && enclosing.name == "m", "got $enclosing")

        val afterMethod = info.code.lastIndexOf("}") // class end
        val nodeBeforeClassEnd = info.metadata.nodeAt(afterMethod - 1)
        assertEquals(RefKind.CLASS, nodeBeforeClassEnd?.refKind)
    }

    @Test
    fun companionObjectNodeEndKeepsNodeAtBalanced() {
        // A companion object records a CLASS definition + NodeEnd, so its closing brace does not
        // over-cancel the enclosing class in nodeAt.
        val cls = irClass("a.Foo")
        val max = com.jadxmp.ir.node.IrField(cls, "MAX", IrType.INT, Flags.PUBLIC or Flags.STATIC or Flags.FINAL)
        max.constValue = com.jadxmp.ir.node.IrFieldConst.Primitive(1L, IrType.INT)
        cls.fields.add(max)
        val info = KotlinCodeGenerator().generate(cls)

        val classEnd = info.code.lastIndexOf("}")
        val enclosing = info.metadata.nodeAt(classEnd - 1)
        assertTrue(enclosing is ClassNodeRef && enclosing.fullName == "a.Foo", "expected class, got $enclosing")
    }
}
