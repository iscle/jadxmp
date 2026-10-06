package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.ir.generics.GenericAttributes
import com.jadxmp.ir.generics.GenericClassDeclaration
import com.jadxmp.ir.generics.GenericDeclarationIndex
import com.jadxmp.ir.generics.TypeParameter
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinExternalArityTest {
    @Test fun suppliedDeclarationsDetermineRawArityButDoNotPolluteBareNames() {
        val root = IrRoot()
        val cls = IrClass(root, "Example", 1, IrType.OBJECT).also(root::addClass)
        root[GenericAttributes.EXTERNAL_DECLARATIONS] = GenericDeclarationIndex(listOf(GenericClassDeclaration(
            "library.PairBox", listOf(parameter("K"), parameter("V")), IrType.OBJECT, emptyList(), emptyList(),
        )))
        val types = KotlinTypeRenderer(KotlinImports("", cls, AliasMap.EMPTY), root = root)
        assertEquals("PairBox<*, *>", types.render(IrType.objectType("library.PairBox")))
        assertEquals("PairBox", types.classNameOf(IrType.objectType("library.PairBox")))
        assertEquals("Unknown", types.render(IrType.objectType("library.Unknown")))
        assertEquals(1, root.classes.size)
    }

    @Test fun programDeclarationWinsOverLibraryAndPlatformFallbacks() {
        val root = IrRoot()
        val cls = IrClass(root, "java.util.List", 1, IrType.OBJECT).also(root::addClass)
        root[GenericAttributes.EXTERNAL_DECLARATIONS] = GenericDeclarationIndex(listOf(GenericClassDeclaration(
            "java.util.List", listOf(parameter("E")), IrType.OBJECT, emptyList(), emptyList(),
        )))
        val types = KotlinTypeRenderer(KotlinImports("", cls, AliasMap.EMPTY), root = root)
        assertEquals("List", types.render(IrType.objectType("java.util.List")))
    }

    private fun parameter(name: String) = TypeParameter(name, IrType.OBJECT, emptyList(), IrType.OBJECT)
}
