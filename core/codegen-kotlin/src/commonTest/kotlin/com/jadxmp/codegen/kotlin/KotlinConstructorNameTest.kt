package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinConstructorNameTest {
    private data class PairModel(val outer: IrClass, val nested: IrClass, val method: IrMethod, val constructor: IrMethod)
    private fun pair(
        methodFlags: Int = Flags.PUBLIC,
        nestedFlags: Int = Flags.PUBLIC or Flags.STATIC,
        superType: IrType = IrType.OBJECT,
    ): PairModel {
        val outer = irClass("example.Collision", accessFlags = Flags.PUBLIC, superType = superType)
        outer.method("<init>") { body(ret()) }
        val child = irClass("example.Collision\$A", root = outer.root, accessFlags = nestedFlags,
            superType = IrType.OBJECT)
        child.outerClass = outer
        outer.innerClasses.add(child)
        val constructor = child.method("<init>") { body(ret()) }
        val function = outer.method("A", IrType.objectType(child.fullName), accessFlags = methodFlags) { body(ret(lit(0, IrType.OBJECT))) }
        return PairModel(outer, child, function, constructor)
    }

    @Test fun suppressesOnlyTheUniqueEmptySignatureDeclarations() {
        val (outer, _, method, constructor) = pair()
        val plan = KotlinConstructorNamePlan(outer, AliasMap.EMPTY)
        assertTrue(plan.suppress(method))
        assertTrue(plan.suppress(constructor))
        assertFalse(plan.suppress(outer.methods.first()))
        assertEquals(2, "CONFLICTING_OVERLOADS".toRegex().findAll(generate(outer)).count())
    }

    @Test fun ambiguousActualMethodsNeverAcquireSuppression() {
        val model = pair()
        model.outer.method("A", IrType.INT) { body(ret(intLit(1))) }
        val plan = KotlinConstructorNamePlan(model.outer, AliasMap.EMPTY)
        assertFalse(plan.suppress(model.method))
        assertFalse(plan.suppress(model.constructor))
        assertNotNull(plan.problem(model.outer))
    }

    @Test fun staticInnerNonPublicAndUnknownInheritanceStayExplicit() {
        for (kind in 0..4) {
            val model = when (kind) {
                0 -> pair(methodFlags = Flags.PUBLIC or Flags.STATIC)
                1 -> pair(nestedFlags = Flags.PUBLIC)
                2 -> pair(nestedFlags = Flags.PRIVATE or Flags.STATIC)
                3 -> pair(nestedFlags = Flags.PROTECTED or Flags.STATIC)
                else -> pair(superType = IrType.objectType("unknown.Parent"))
            }
            val plan = KotlinConstructorNamePlan(model.outer, AliasMap.EMPTY)
            assertFalse(plan.suppress(model.method))
            assertNotNull(plan.problem(model.outer))
        }
    }

    @Test fun budgetFailureRetainsHealthyMemberAndMakesNoSuppression() {
        val model = pair()
        val plan = KotlinConstructorNamePlan(model.outer, AliasMap.EMPTY, workLimit = 1)
        assertNotNull(plan.problem(model.outer))
        assertFalse(plan.suppress(model.method))
        repeat(1000) { assertEquals(null, plan.constructorClass(IrType.objectType(model.nested.fullName))) }
    }
    @Test fun explicitOuterRenameAndConflictingMemberAliasesRemainCoherent() {
        val model = pair()
        model.outer.method("construct", IrType.objectType(model.nested.fullName)) {
            body(ret(expr(constructor(IrType.objectType(model.nested.fullName), emptyList(), emptyList()))))
        }
        val aliases = AliasMap.of(mapOf(ClassNodeRef(model.outer.fullName) to "Renamed"))
        val output = KotlinCodeGenerator().generate(model.outer, aliases).code
        assertTrue(output.contains("unsupported or ambiguous constructor/function source-name collision"), output)
        assertFalse(output.contains("CONFLICTING_OVERLOADS"), output)
        val duplicate = model.outer.method("other", IrType.INT) { body(ret(intLit(1))) }
        val collision = AliasMap.of(mapOf(MethodNodeRef(model.outer.fullName, duplicate.name, emptyList()) to "A"))
        val plan = KotlinConstructorNamePlan(model.outer, collision)
        assertFalse(plan.suppress(model.method))
        assertNotNull(plan.problem(model.outer))
    }

    @Test fun longExternalNamesExhaustAggregateBudgetWithoutLosingHealthyBody() {
        val model = pair()
        repeat(1000) { index -> model.outer.method("m".repeat(2000) + index) }
        val caller = irClass("example.Caller", root = model.outer.root, superType = IrType.OBJECT)
        caller.method("healthy", IrType.INT) { body(ret(intLit(7))) }
        caller.method("construct", IrType.objectType(model.nested.fullName)) {
            body(ret(expr(constructor(IrType.objectType(model.nested.fullName), emptyList(), emptyList()))))
        }
        val output = generate(caller)
        assertTrue(output.contains("constructor/function name analysis work limit exceeded"), output)
        assertTrue(output.contains("return 7"), output)
    }

    @Test fun dynamicConstructorAliasAlsoNamesItsOrdinaryBinaryTypeReferences() {
        val model = pair()
        val imports = KotlinImports("example", model.outer, AliasMap.EMPTY)
        imports.useClass(model.nested.fullName)
        imports.constructorAlias(IrType.objectType(model.nested.fullName))
        imports.finishDiscovery()
        assertEquals(imports.constructorAlias(IrType.objectType(model.nested.fullName)), imports.useClass(model.nested.fullName))
    }

    @Test fun dynamicallyAliasedTopLevelOwnerAlsoNamesSelfTypes() {
        val cls = irClass("example.i")
        val imports = KotlinImports("example", cls, AliasMap.EMPTY)
        imports.useClass(cls.fullName)
        imports.aliasedClass(cls.fullName)
        imports.finishDiscovery()
        assertEquals(imports.aliasedClass(cls.fullName), imports.useClass(cls.fullName))
    }

    @Test fun unregisteredDollarIdentityIsNotConflatedWithDottedPackageOwner() {
        val cls = irClass("example.User")
        val imports = KotlinImports("example", cls, AliasMap.EMPTY)
        val untouched = imports.useClass("other.Outer\$A")
        imports.aliasedClass("other.Outer.A")
        imports.finishDiscovery()
        assertEquals(untouched, imports.useClass("other.Outer\$A"))
        assertFalse(imports.useClass("other.Outer\$A") == imports.aliasedClass("other.Outer.A"))
    }

    @Test fun unknownCallerHierarchyIsDiagnosedWithoutLosingHealthySibling() {
        val model = pair()
        val caller = irClass("example.Caller", root = model.outer.root,
            superType = IrType.objectType("unknown.Base"))
        caller.method("healthy", IrType.INT) { body(ret(intLit(7))) }
        caller.method("construct", IrType.objectType(model.nested.fullName)) {
            body(ret(expr(constructor(IrType.objectType(model.nested.fullName), emptyList(), emptyList()))))
        }
        val output = generate(caller)
        assertTrue(output.contains("constructor alias requires a complete caller name scope"), output)
        assertTrue(output.contains("return 7"), output)
        assertFalse(output.contains("return JvmA()"), output)
    }

}
