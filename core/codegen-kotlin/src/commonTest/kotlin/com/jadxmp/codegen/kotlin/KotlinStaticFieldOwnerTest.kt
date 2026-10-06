package com.jadxmp.codegen.kotlin

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

class KotlinStaticFieldOwnerTest {
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
        val code = KotlinCodeGenerator().generate(cls, AliasMap.of(mapOf(ClassNodeRef("Original") to "i"))).code
        assertFalse(Regex("(?<![A-Za-z0-9_$])i\\.value").containsMatchIn(code), code)
        assertTrue(code.contains("value"), code)
    }

    @Test fun nestedFieldOwnerAliasAlsoSpellsItsBinaryTypeReferences() {
        val outer = irClass("p.Outer")
        val nested = irClass("p.Outer$" + "Inner", root = outer.root)
        nested.outerClass = outer
        outer.innerClasses.add(nested)
        val imports = KotlinImports("p", outer, AliasMap.EMPTY)
        val types = KotlinTypeRenderer(imports, root = outer.root)
        val type = IrType.objectType(nested.fullName)
        types.aliasedFieldOwner(type)
        imports.finishDiscovery()
        val alias = types.aliasedFieldOwner(type)
        kotlin.test.assertEquals(alias, types.render(type))
        kotlin.test.assertEquals("Array<$alias?>", types.render(IrType.array(type)))
        kotlin.test.assertEquals(listOf("p.Outer.Inner" to alias), imports.imports())
    }

    @Test fun incompleteFieldScopeDiagnosesOnlyTheAffectedMethod() {
        val cls = irClass("Owner", superType = IrType.objectType("external.Base"))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val field = FieldRef(IrType.objectType("Owner"), "value", IrType.INT)
        val affected = cls.method("read", returnType = IrType.INT, accessFlags = Flags.PUBLIC) {
            body(ret(expr(staticGet(field))))
        }
        val healthy = cls.method("healthy", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(intLit(9)))
        }
        val code = generate(cls)
        assertTrue(affected.contains(com.jadxmp.ir.attr.AttrFlag.HAS_ERROR), code)
        assertFalse(healthy.contains(com.jadxmp.ir.attr.AttrFlag.HAS_ERROR), code)
        assertTrue(code.contains("owned static field binding is shadowed in an unresolved source scope"), code)
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

    @Test fun hoistedInitializerHasNoStaticBodyReceiver() {
        val cls = irClass("Owner", superType = IrType.OBJECT)
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val field = FieldRef(IrType.objectType(cls.fullName), "value", IrType.INT)
        val method = cls.method("<clinit>", accessFlags = Flags.STATIC) { }
        val store = FieldInstruction(field, isStatic = true, isPut = true, args = listOf(expr(staticGet(field))))
        val code = com.jadxmp.codegen.CodeWriter()
        val writer = MethodBodyWriter(code, KotlinImports("", cls, AliasMap.EMPTY), method,
            com.jadxmp.codegen.NameGenerator(), emptyList(), staticPropertyContainer = true)
        assertTrue(writer.emitStaticFinalInit(store))
        kotlin.test.assertEquals("Owner.value", code.currentText())
    }

    @Test fun loadedInheritedTypeCannotCaptureOwnedInstanceQualifier() {
        val base = irClass("p.Base", superType = IrType.OBJECT)
        val inherited = irClass("p.Base$" + "Owner", root = base.root, superType = IrType.OBJECT)
        inherited.outerClass = base
        base.innerClasses.add(inherited)
        val cls = irClass("p.Owner", root = base.root, superType = IrType.objectType(base.fullName))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        cls.method("read", returnType = IrType.INT, accessFlags = Flags.PUBLIC) {
            body(ret(expr(staticGet(FieldRef(IrType.objectType(cls.fullName), "value", IrType.INT)))))
        }
        val code = generate(cls)
        assertTrue(code.contains("import p.Owner as JvmOwner"), code)
        assertTrue(code.contains("return JvmOwner.value"), code)
    }

    @Test fun ownedFieldOwnerTextIsChargedOnEveryCachedLookup() {
        val cls = irClass("Owner".repeat(100))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val imports = KotlinImports("", cls, AliasMap.EMPTY, ownFieldWork = 3000)
        val ref = FieldRef(IrType.objectType(cls.fullName.toCharArray().concatToString()), "value", IrType.INT)
        kotlin.test.assertEquals(KotlinImports.Ownership.OWNED, imports.ownedMutableField(cls, ref))
        repeat(5) { imports.ownedMutableField(cls, ref) }
        kotlin.test.assertEquals(KotlinImports.Ownership.UNAVAILABLE, imports.ownedMutableField(cls, ref))
    }

}
