package com.jadxmp.codegen

import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InvocationSourceBindingTest {
    @Test fun inheritedReceiverOverloadsAndAllArgumentsAreBound() {
        val root = IrRoot()
        val base = cls(root, "Base")
        val child = cls(root, "Child", type("Base"))
        method(base, listOf(IrType.OBJECT, IrType.OBJECT))
        method(child, listOf(IrType.STRING, IrType.STRING))
        val binding = InvocationSourceBinding(root)
        assertEquals(setOf(0, 1), binding.arguments(ref("Base", listOf(IrType.OBJECT, IrType.OBJECT)),
            type("Child"), listOf(IrType.STRING, IrType.STRING)))
    }

    @Test fun unknownAndNarrowingRelationsDoNotInventRuntimeCasts() {
        val root = IrRoot()
        val owner = cls(root, "Owner")
        method(owner, listOf(type("Left")))
        method(owner, listOf(type("Right")))
        val binding = InvocationSourceBinding(root)
        assertEquals(emptySet(), binding.arguments(ref("Owner", listOf(type("Left"))), null, listOf(type("Right"))))
        assertEquals(emptySet(), binding.arguments(ref("Owner", listOf(type("Right"))), null, listOf(IrType.OBJECT)))
    }

    @Test fun loadedDefinitionsOverridePlatformAncestryAndConstructorsAreNotInherited() {
        val root = IrRoot()
        cls(root, "java.lang.Exception", IrType.OBJECT)
        val owner = cls(root, "Owner")
        val throwable = type("java.lang.Throwable")
        method(owner, listOf(throwable))
        method(owner, listOf(type("java.lang.Exception")))
        val binding = InvocationSourceBinding(root)
        assertEquals(emptySet(), binding.arguments(ref("Owner", listOf(throwable)), null, listOf(type("java.lang.Exception"))))
        val child = cls(root, "Child", type("Owner"))
        method(owner, listOf(IrType.OBJECT), "<init>")
        method(child, listOf(IrType.STRING), "<init>")
        assertEquals(emptySet(), binding.arguments(ref("Child", listOf(IrType.OBJECT), "<init>"), null, listOf(IrType.STRING)))
    }

    @Test fun referenceArrayWideningDoesNotInventPrimitiveArrayCovariance() {
        val root = IrRoot()
        val owner = cls(root, "Owner")
        val objects = IrType.array(IrType.OBJECT)
        val strings = IrType.array(IrType.STRING)
        method(owner, listOf(objects))
        method(owner, listOf(strings))
        val binding = InvocationSourceBinding(root)
        assertEquals(setOf(0), binding.arguments(ref("Owner", listOf(objects)), null, listOf(strings)))
        assertEquals(emptySet(), binding.arguments(ref("Owner", listOf(objects)), null, listOf(IrType.array(IrType.INT))))
    }

    @Test fun varargsCompetitionAndParameterizedTypesStayOutsideTheProof() {
        val root = IrRoot()
        val owner = cls(root, "Owner")
        method(owner, listOf(IrType.OBJECT))
        owner.methods.add(IrMethod(owner, "call", IrType.VOID, listOf(IrType.array(IrType.STRING)), 0x81))
        val binding = InvocationSourceBinding(root)
        assertEquals(emptySet(), binding.arguments(ref("Owner", listOf(IrType.OBJECT)), null,
            listOf(IrType.array(IrType.STRING))))

        val genericOwner = cls(root, "GenericOwner")
        val parameterized = IrType.generic("List", IrType.STRING)
        method(genericOwner, listOf(parameterized))
        method(genericOwner, listOf(IrType.OBJECT))
        assertEquals(emptySet(), binding.arguments(ref("GenericOwner", listOf(parameterized)), null,
            listOf(parameterized)))
        assertEquals(emptySet(), binding.arguments(ref("GenericOwner", listOf(IrType.OBJECT)), null,
            listOf(parameterized)))
    }

    @Test fun repeatedCallsReuseBoundedAnalysisAndCyclesTerminate() {
        val root = IrRoot()
        val owner = cls(root, "Owner", type("Cycle"))
        cls(root, "Cycle", type("Owner"))
        method(owner, listOf(IrType.OBJECT))
        method(owner, listOf(IrType.STRING))
        // Long source ancestry is proved once, not once per use.
        var parent: IrType = IrType.OBJECT
        repeat(2_000) { index ->
            val name = "Chain$index"
            cls(root, name, parent)
            parent = type(name)
        }
        method(owner, listOf(type("Chain0")))
        val binding = InvocationSourceBinding(root)
        repeat(20_000) {
            assertEquals(setOf(0), binding.arguments(ref("Owner", listOf(type("Chain0"))), null, listOf(parent)))
        }
        assertFailsWith<IllegalStateException> {
            InvocationSourceBinding(root, maxWork = 1).arguments(ref("Owner", listOf(IrType.OBJECT)), null, listOf(IrType.STRING))
        }
    }

    private fun type(name: String) = IrType.objectType(name)
    private fun cls(root: IrRoot, name: String, parent: IrType? = null) = IrClass(root, name, 1, parent).also(root::addClass)
    private fun method(owner: IrClass, args: List<IrType>, name: String = "call") {
        owner.methods.add(IrMethod(owner, name, IrType.VOID, args, 1))
    }
    private fun ref(owner: String, args: List<IrType>, name: String = "call") = MethodRef(type(owner), name, IrType.VOID, args)
}
