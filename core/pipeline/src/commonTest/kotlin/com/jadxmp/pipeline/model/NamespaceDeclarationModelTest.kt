package com.jadxmp.pipeline.model

import com.jadxmp.input.*
import com.jadxmp.ir.declaration.*
import kotlin.test.*

class NamespaceDeclarationModelTest {
    private fun cls(name: String = "Lp/Base;", parent: String? = "Ljava/lang/Object;",
        nesting: ClassNesting? = null, members: List<MemberTypeDeclarationData>? = null) =
        ClassDeclarationData(name, 1, parent, emptyList(), emptyList(), emptyList(),
            genericSignature = "not a valid generic signature", nesting = nesting, memberTypes = members)

    private fun get(index: NamespaceDeclarationIndex, name: String = "Lp/Base;") =
        (index.lookup(name, NamespaceWorkBudget()) as NamespaceLookup.Known).declaration

    @Test fun namespaceCapabilitiesDoNotDependOnGenericProofAndUnknownIsNotEmpty() {
        val unknown = get(NamespaceDeclarationModel.build(listOf(cls())))
        assertEquals(NamespaceFact.Unknown, unknown.nesting)
        assertEquals(NamespaceFact.Unknown, unknown.memberTypes)
        assertIs<NamespaceFact.Available<*>>(unknown.hierarchy)
        val known = get(NamespaceDeclarationModel.build(listOf(cls(nesting = ClassNesting.TopLevel, members = emptyList()))))
        assertEquals(NamespaceFact.Available(NamespaceNesting.TopLevel), known.nesting)
        assertEquals(NamespaceFact.Available(emptyList()), known.memberTypes)
    }

    @Test fun snapshotsPreserveExactMembersAndEnclosureWithoutDollarInference() {
        val members = mutableListOf(MemberTypeDeclarationData("Lp/Base\$Tag;", "Tag", 0x2609))
        val parents = mutableListOf("Lp/Marker;")
        val data = cls(members = members).copy(interfaces = parents)
        val nested = cls("Lp/Base\$Tag;", nesting = ClassNesting.Nested("Lp/Base;", innerName = "Tag"), members = emptyList())
        val index = NamespaceDeclarationModel.build(listOf(data, nested))
        members.clear(); parents.clear()
        val declaration = get(index)
        assertEquals("Tag", (declaration.memberTypes as NamespaceFact.Available).value.single().innerName)
        assertEquals(listOf("Lp/Marker;"), (declaration.hierarchy as NamespaceFact.Available).value.interfaces)
        assertEquals(NamespaceFact.Available(NamespaceNesting.Nested("Lp/Base;", "Tag", null)), get(index, nested.type).nesting)
        assertEquals(NamespaceLookup.Missing, index.lookup("Lp/Missing;", NamespaceWorkBudget()))
    }

    @Test fun localEnclosingMethodIdentityIsSnapshottedWithoutLoadingItsBody() {
        val parameters = mutableListOf("I", "[Ljava/lang/String;")
        val method = object : MethodRef {
            override val declaringClassType = "Lp/Outer;"
            override val name = "run"
            override val parameterTypes = parameters
            override val returnType = "Ljava/lang/Object;"
        }
        val index = NamespaceDeclarationModel.build(listOf(cls(nesting = ClassNesting.Nested("Lp/Outer;", method, "Local"))))
        parameters.clear()
        val nesting = (get(index).nesting as NamespaceFact.Available).value as NamespaceNesting.Nested
        assertEquals(NamespaceEnclosingMethod("Lp/Outer;", "run", listOf("I", "[Ljava/lang/String;"), "Ljava/lang/Object;"), nesting.method)
    }

    @Test fun enclosingMethodGrammarAndConstructorReturnAreValidatedIndependently() {
        fun model(name: String, result: String): NamespaceDeclaration {
            val method = object : MethodRef {
                override val declaringClassType = "Lp/Outer;"
                override val name = name
                override val parameterTypes = emptyList<String>()
                override val returnType = result
            }
            return get(NamespaceDeclarationModel.build(listOf(cls(nesting = ClassNesting.Nested("Lp/Outer;", method, "Local"), members = emptyList()))))
        }
        for ((name, result) in listOf("bad<name>" to "V", "<clinit>" to "V", "<init>" to "I", "bad>" to "V")) {
            val declaration = model(name, result)
            assertIs<NamespaceFact.Invalid>(declaration.nesting, name)
            assertIs<NamespaceFact.Available<*>>(declaration.hierarchy)
            assertEquals(NamespaceFact.Available(emptyList()), declaration.memberTypes)
        }
        assertIs<NamespaceFact.Available<*>>(model("<init>", "V").nesting)
        val initializer = get(NamespaceDeclarationModel.build(listOf(cls(nesting = ClassNesting.Nested("Lp/Outer;", innerName = "Local")))))
        assertEquals(NamespaceFact.Available(NamespaceNesting.Nested("Lp/Outer;", "Local", null)), initializer.nesting)
    }

    @Test fun malformedAndContradictoryCapabilitiesNeverBecomeComplete() {
        val bad = cls(parent = "[I", members = listOf(
            MemberTypeDeclarationData("Lp/A;", "Tag", 1), MemberTypeDeclarationData("Lp/B;", "Tag", 1)))
        val declaration = get(NamespaceDeclarationModel.build(listOf(bad)))
        assertIs<NamespaceFact.Invalid>(declaration.hierarchy)
        assertIs<NamespaceFact.Invalid>(declaration.memberTypes)
        assertFailsWith<IllegalArgumentException> { NamespaceDeclarationModel.build(listOf(cls(), cls())) }
        assertFailsWith<IllegalArgumentException> { NamespaceDeclarationModel.build(listOf(cls("[I"))) }
    }

    @Test fun cyclesInvalidateOnlyAffectedCapabilitiesAndKeepIndependentInventory() {
        val a = cls("Lp/A;", "Lp/B;", ClassNesting.Nested("Lp/B;", innerName = "A"), emptyList())
        val b = cls("Lp/B;", "Lp/A;", ClassNesting.Nested("Lp/A;", innerName = "B"), emptyList())
        val index = NamespaceDeclarationModel.build(listOf(a, b, cls()))
        for (name in listOf(a.type, b.type)) {
            val declaration = get(index, name)
            assertIs<NamespaceFact.Invalid>(declaration.hierarchy)
            assertIs<NamespaceFact.Invalid>(declaration.nesting)
            assertEquals(NamespaceFact.Available(emptyList()), declaration.memberTypes)
        }
        assertIs<NamespaceFact.Available<*>>(get(index).hierarchy)
    }

    @Test fun incompatibleOwnNestingAndOwnerInventoryRemainUnavailable() {
        val owner = cls(members = listOf(MemberTypeDeclarationData("Lp/Child;", "Tag", 1)))
        val child = cls("Lp/Child;", nesting = ClassNesting.TopLevel, members = emptyList())
        val index = NamespaceDeclarationModel.build(listOf(owner, child))
        assertEquals(child.innerAccessFlags, get(index, child.type).innerAccessFlags)
        assertIs<NamespaceFact.Invalid>(get(index).memberTypes)
        assertIs<NamespaceFact.Invalid>(get(index, child.type).nesting)
        assertIs<NamespaceFact.Available<*>>(get(index).hierarchy)
    }

    @Test fun missingSuperclassDoesNotInventRootAndMalformedEnclosingMethodIsInvalid() {
        assertIs<NamespaceFact.Invalid>(get(NamespaceDeclarationModel.build(listOf(cls(parent = null)))).hierarchy)
        val method = object : MethodRef {
            override val declaringClassType = "Lp/Other;"
            override val name = "run"
            override val parameterTypes = listOf("[V")
            override val returnType = "V"
        }
        val data = cls(nesting = ClassNesting.Nested("Lp/Outer;", method, "Local"))
        assertIs<NamespaceFact.Invalid>(get(NamespaceDeclarationModel.build(listOf(data))).nesting)
    }

    @Test fun exactOwnMemberModifiersRemainDistinctFromClassFlags() {
        val child = cls("Lp/Child;", nesting = ClassNesting.Nested("Lp/Base;", innerName = "Tag"), members = emptyList())
            .copy(innerAccessFlags = 0x000a)
        val owner = cls(members = listOf(MemberTypeDeclarationData(child.type, "Tag", 0x0009)))
        val index = NamespaceDeclarationModel.build(listOf(owner, child))
        assertEquals(child.innerAccessFlags, get(index, child.type).innerAccessFlags)
        assertIs<NamespaceFact.Invalid>(get(index).memberTypes)
        assertIs<NamespaceFact.Invalid>(get(index, child.type).nesting)
    }

    @Test fun longGraphsAreIterativeAndBounded() {
        val records = (0 until 2000).map { i -> cls("Lp/C$i;", if (i == 1999) "Ljava/lang/Object;" else "Lp/C${i + 1};") }
        assertEquals(2000, NamespaceDeclarationModel.build(records).size)
        assertFailsWith<IllegalArgumentException> { NamespaceDeclarationModel.build(records, NamespaceWorkBudget(100)) }
    }

    @Test fun repeatedLongLookupChargesBeforeHashAndBuildExhaustionFailsClosed() {
        val name = "Lp/" + "A".repeat(1000) + ";"
        val index = NamespaceDeclarationModel.build(listOf(cls(name)))
        val budget = NamespaceWorkBudget(2500)
        assertIs<NamespaceLookup.Known>(index.lookup(name, budget))
        assertIs<NamespaceLookup.Known>(index.lookup(name, budget))
        assertIs<NamespaceLookup.Unavailable>(index.lookup(name, budget))
        repeat(1000) { assertIs<NamespaceLookup.Unavailable>(index.lookup("Lp/Other$it;", budget)) }
        assertEquals(1, index.size)
        assertFailsWith<IllegalArgumentException> { NamespaceDeclarationModel.build(listOf(cls(name)), NamespaceWorkBudget(100)) }
    }
}
