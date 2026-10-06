package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.node.IrField
import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaStaticFieldOwnerTest {
    @Test fun parameterCannotCaptureStaticFieldOwnerOnReadOrWrite() {
        val cls = irClass("i", superType = IrType.OBJECT)
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val field = FieldRef(IrType.objectType("i"), "value", IrType.INT)
        val parameter = Local(0, IrType.INT, name = "i", isParam = true)
        cls.method("read", returnType = IrType.INT, argTypes = listOf(IrType.INT), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            this[CodegenKeys.PARAM_NAMES] = listOf("i")
            body(FieldInstruction(field, isStatic = true, isPut = true, args = listOf(parameter.ref())), ret(expr(staticGet(field))))
        }
        val code = generate(cls)
        assertFalse(Regex("(?<![A-Za-z0-9_$])i\\.value").containsMatchIn(code), code)
        assertTrue(code.contains("value"), code)
    }
    @Test fun sourceFieldAndRenamedOwnerCannotCaptureStaticQualifier() {
        val cls = irClass("Original")
        cls.fields.add(IrField(cls, "i", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val field = FieldRef(IrType.objectType("Original"), "value", IrType.INT)
        cls.method("read", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticGet(field))))
        }
        val code = JavaCodeGenerator().generate(cls, AliasMap.of(mapOf(ClassNodeRef("Original") to "i"))).code
        assertFalse(Regex("(?<![A-Za-z0-9_$])i\\.value").containsMatchIn(code), code)
        assertTrue(code.contains("value"), code)
    }

    @Test fun loadedInheritedTypeCannotCaptureOwnFieldQualifier() {
        val base = irClass("p.Base")
        val inherited = irClass("p.Base$" + "Owner", root = base.root)
        inherited.outerClass = base
        base.innerClasses.add(inherited)
        val cls = irClass("p.Owner", root = base.root, superType = IrType.objectType(base.fullName))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val field = FieldRef(IrType.objectType(cls.fullName), "value", IrType.INT)
        cls.method("read", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticGet(field))))
        }
        val code = generate(cls)
        assertTrue(code.contains("return value;"), code)
        assertFalse(code.contains("Owner.value"), code)
    }

    @Test fun incompleteScopeWithLocalCapturingOwnFieldHasAnExplicitDiagnostic() {
        val cls = irClass("Owner", superType = IrType.objectType("absent.Base"))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val field = FieldRef(IrType.objectType(cls.fullName), "value", IrType.INT)
        val method = cls.method("read", returnType = IrType.INT, argTypes = listOf(IrType.INT), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(expr(staticGet(field))))
        }
        val code = generate(cls)
        assertTrue(method.contains(com.jadxmp.ir.attr.AttrFlag.HAS_ERROR), code)
        assertTrue(code.contains("owned static field binding is shadowed"), code)
    }

    @Test fun foreignFieldAccessKeepsExistingOwnerPath() {
        val cls = irClass("Owner", superType = IrType.objectType("absent.Base"))
        val field = FieldRef(IrType.objectType("external.Foreign"), "value", IrType.INT)
        val method = cls.method("read", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticGet(field))))
        }
        val code = generate(cls)
        assertTrue(code.contains("Foreign.value"), code)
        assertFalse(method.contains(com.jadxmp.ir.attr.AttrFlag.HAS_ERROR), code)
    }

    @Test fun enumArgumentContextKeepsQualifiedReference() {
        val cls = irClass("Owner", superType = IrType.OBJECT)
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val field = FieldRef(IrType.objectType(cls.fullName), "value", IrType.INT)
        val method = cls.method("<clinit>", accessFlags = Flags.STATIC) { }
        val code = com.jadxmp.codegen.CodeWriter()
        val writer = MethodBodyWriter(code, com.jadxmp.codegen.ImportCollector(), method,
            com.jadxmp.codegen.NameGenerator(), emptyList())
        assertTrue(writer.emitEnumConstantArgs(listOf(expr(staticGet(field))), listOf(IrType.INT)))
        kotlin.test.assertEquals("(Owner.value)", code.currentText())
    }

}
