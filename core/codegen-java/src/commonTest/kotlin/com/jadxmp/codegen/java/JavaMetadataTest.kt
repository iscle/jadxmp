package com.jadxmp.codegen.java

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

class JavaMetadataTest {

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
        JavaCodeGenerator().generate(cls)
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
        // "int i = ..." — the variable 'i' sits one token after "int ".
        val offset = info.code.indexOf("int i =") + "int ".length
        val ann = info.metadata.at(offset)
        assertTrue(ann is VariableAnnotation && ann.declaration && ann.ref.name == "i", "got $ann")
    }

    @Test
    fun nodeAtResolvesEnclosingMethodThenClass() {
        val info = sample()
        val insideBody = info.code.indexOf("parseInt")
        val enclosing = info.metadata.nodeAt(insideBody)
        assertTrue(enclosing is MethodNodeRef && enclosing.name == "m", "got $enclosing")

        // A position after the method's closing brace but before the class brace resolves to the class.
        val afterMethod = info.code.lastIndexOf("}") // class end
        val nodeBeforeClassEnd = info.metadata.nodeAt(afterMethod - 1)
        assertEquals(RefKind.CLASS, nodeBeforeClassEnd?.refKind)
    }

    @Test
    fun nodeAtStaysBalancedAcrossBodylessMethods() {
        // Body-less (abstract/interface) methods still emit a NodeEnd, so their METHOD definitions do
        // not leak as enclosing scopes. Without the NodeEnd the class brace would resolve to a method.
        val cls = irClass("a.Iface", accessFlags = Flags.PUBLIC or Flags.ABSTRACT or Flags.INTERFACE)
        cls.method("m1", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        cls.method("m2", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        val info = JavaCodeGenerator().generate(cls)

        val classEnd = info.code.lastIndexOf("}")
        val enclosing = info.metadata.nodeAt(classEnd - 1)
        assertTrue(enclosing is ClassNodeRef && enclosing.fullName == "a.Iface", "expected class, got $enclosing")
    }
}
