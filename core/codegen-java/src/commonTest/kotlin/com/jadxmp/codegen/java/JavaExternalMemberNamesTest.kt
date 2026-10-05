package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaExternalMemberNamesTest {
    @Test
    fun contextualKeywordsRemainLegalQualifiedLibraryMembers() {
        val owner = IrType.objectType("library.External")
        for (name in listOf("var", "record", "yield", "sealed", "permits")) {
            assertEquals(name, JavaMemberAliases.aliasForMethodRef(null, MethodRef(owner, name, IrType.INT, emptyList())))
            assertEquals(name, JavaMemberAliases.aliasForFieldRef(null, FieldRef(owner, name, IrType.INT)))
            assertEquals(name + "Word", JavaIdentifiers.sanitize(name), "still forbidden as a type")
        }
    }
}
