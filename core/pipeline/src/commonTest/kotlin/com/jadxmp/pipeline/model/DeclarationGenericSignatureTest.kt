package com.jadxmp.pipeline.model

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.generics.GenericAttributes
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeClassData
import com.jadxmp.pipeline.support.FakeCodeLoader
import com.jadxmp.pipeline.support.FakeFieldData
import com.jadxmp.pipeline.support.FakeFieldRef
import com.jadxmp.pipeline.support.FakeMethodData
import com.jadxmp.pipeline.support.FakeMethodRef
import kotlin.test.Test
import com.jadxmp.input.ClassData
import com.jadxmp.pipeline.pass.CancellationSignal
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeclarationGenericSignatureTest {
    @Test fun executableBuildDoesNotReadOrAttachSourceGenericMetadataYet() {
        val input = object : ClassData by FakeClassData("LExecutable;") {
            override val genericSignature: String? get() = error("metadata getter must stay unused")
        }
        val cls = ModelBuilder.build(FakeCodeLoader(listOf(input))).classes.single()
        assertNull(cls[GenericAttributes.CLASS])
        assertNull(cls[GenericAttributes.RECOVERIES])
        assertNull(cls[IrAttrs.ERROR])
    }

    @Test fun knownReflectiveScopeRecoversOnlyTrulyUndefinedVariables() {
        for (known in listOf(false, true)) {
            val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(FakeClassData("LUndefined;",
                nesting = if (known) com.jadxmp.input.ClassNesting.TopLevel else null,
                fields = listOf(FakeFieldData(FakeFieldRef("LUndefined;", "value", "Ljava/lang/Object;"), genericSignature = "TZ;"))
            )))).classes.single()
            assertEquals(!known, cls.contains(AttrFlag.HAS_ERROR))
            assertEquals(known, cls[GenericAttributes.RECOVERIES] != null)
            assertEquals(IrType.OBJECT, cls.fields.single().type)
        }
    }

    @Test fun reflectiveAbsenceDoesNotInheritBinaryNameGuessedScope() {
        val outer = FakeClassData("LOuter;", nesting = com.jadxmp.input.ClassNesting.TopLevel,
            genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;")
        val child = FakeClassData("LOuter\$Member;", reflectiveNesting = com.jadxmp.input.ClassNesting.TopLevel,
            fields = listOf(FakeFieldData(FakeFieldRef("LOuter\$Member;", "value", "Ljava/lang/Object;"), genericSignature = "TT;")))
        val root = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(outer, child)))
        val cls = root.findClass("Outer\$Member")!!
        assertEquals(root.findClass("Outer"), cls.outerClass) // source reconstruction contract unchanged
        assertNull(cls.fields.single()[GenericAttributes.FIELD])
        assertNotNull(cls[GenericAttributes.RECOVERIES])
        assertNull(cls[IrAttrs.ERROR])
    }

    @Test fun completeScopeDoesNotReclassifyStaticSourceRestrictionsAsMalformedMetadata() {
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(FakeClassData("LStaticScope;",
            nesting = com.jadxmp.input.ClassNesting.TopLevel,
            genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            fields = listOf(FakeFieldData(FakeFieldRef("LStaticScope;", "value", "Ljava/lang/Object;"),
                accessFlags = com.jadxmp.input.AccessFlags.STATIC, genericSignature = "TT;")),
            methods = listOf(FakeMethodData(FakeMethodRef("LStaticScope;", "echo", "Ljava/lang/Object;", listOf("Ljava/lang/Object;")),
                accessFlags = com.jadxmp.input.AccessFlags.STATIC, genericSignature = "(TT;)TT;"))
        )))).classes.single()
        assertTrue(cls.contains(AttrFlag.HAS_ERROR))
        assertTrue(cls.methods.single().contains(AttrFlag.HAS_ERROR))
        assertNull(cls[GenericAttributes.RECOVERIES])
        assertNull(cls.methods.single()[GenericAttributes.RECOVERIES])
    }

    @Test fun undefinedInterfaceVariableKeepsIndependentlyValidFormalDeclarations() {
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(FakeClassData("LMissingVariable;",
            nesting = com.jadxmp.input.ClassNesting.TopLevel,
            interfaces = listOf("Ljava/util/function/Function;"),
            genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/util/function/Function<TZ;TZ;>;",
            fields = listOf(FakeFieldData(FakeFieldRef("LMissingVariable;", "value", "Ljava/lang/Object;"), genericSignature = "TT;"))
        )))).classes.single()
        assertNull(cls[IrAttrs.ERROR])
        assertNotNull(cls[GenericAttributes.RECOVERIES])
        assertEquals("T", cls[GenericAttributes.CLASS]?.parameters?.single()?.name)
        assertEquals(IrType.typeVariable("T"), cls.fields.single()[GenericAttributes.FIELD])
    }

    @Test fun malformedFieldDiagnosticsAccumulateWithoutDroppingMembersOrNotices() {
        val count = 20_000
        val fields = (0 until count).map { index -> FakeFieldData(
            FakeFieldRef("LBrokenFields;", "field$index", "I"), genericSignature = "I") }
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(FakeClassData("LBrokenFields;", fields = fields))))
            .classes.single()
        assertEquals(count, cls.fields.size)
        assertEquals(count, cls[GenericAttributes.RECOVERIES]!!.size)
        assertTrue(cls.fields.all { it[GenericAttributes.RECOVERIES]!!.size == 1 })
        assertNull(cls[IrAttrs.ERROR])
    }

    @Test fun repeatedFieldSignaturesRetainClassAndStaticScopes() {
        fun input(name: String, bound: String) = FakeClassData("L$name;",
            nesting = com.jadxmp.input.ClassNesting.TopLevel,
            genericSignature = "<T:L$bound;>Ljava/lang/Object;",
            fields = listOf(
                FakeFieldData(FakeFieldRef("L$name;", "first", "L$bound;"), genericSignature = "TT;"),
                FakeFieldData(FakeFieldRef("L$name;", "second", "L$bound;"), genericSignature = "TT;"),
                FakeFieldData(FakeFieldRef("L$name;", "staticField", "L$bound;"),
                    accessFlags = com.jadxmp.input.AccessFlags.STATIC, genericSignature = "TT;"),
            ))
        val classes = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(
            input("StringScope", "java/lang/String"), input("NumberScope", "java/lang/Number"),
        ))).classes
        for (cls in classes) {
            assertTrue(cls.fields.take(2).all { it[GenericAttributes.FIELD] == IrType.typeVariable("T") })
            assertNull(cls.fields.last()[GenericAttributes.FIELD])
            assertNotNull(cls.fields.last()[IrAttrs.ERROR])
            assertNull(cls[GenericAttributes.RECOVERIES])
        }
    }

    @Test fun cachedFieldSignaturesStillConsumeTheAggregateWorkBudget() {
        val signature = "L" + "x".repeat(10_000) + ";"
        val fields = (0 until 150).map { index -> FakeFieldData(
            FakeFieldRef("LCached;", "field$index", signature), genericSignature = signature) }
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(FakeClassData("LCached;", fields = fields))))
            .classes.single()
        assertEquals(150, cls.fields.size)
        assertEquals(99, cls.fields.count { it[GenericAttributes.FIELD] != null })
        assertTrue(cls[IrAttrs.ERROR]!!.message.contains("aggregate generic signature work limit"))
        assertNull(cls[GenericAttributes.RECOVERIES])
    }

    @Test fun repeatedLargeLexicalScopesHaveAnAggregateWorkLimit() {
        val formals = (0 until 1500).joinToString("") { "T$it:Ljava/lang/Object;" }
        val data = FakeClassData("LWide;", genericSignature = "<$formals>Ljava/lang/Object;",
            fields = (0 until 1000).map { index -> FakeFieldData(
                FakeFieldRef("LWide;", "field$index", "Ljava/lang/Object;"), genericSignature = "TT0;") })
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(data))).classes.single()
        assertTrue(cls.contains(AttrFlag.HAS_ERROR))
        assertTrue(cls[IrAttrs.ERROR]!!.message.contains("aggregate generic signature work limit"))
        assertNull(cls[GenericAttributes.RECOVERIES])
        assertTrue(cls.fields.any { it[GenericAttributes.FIELD] != null })
        assertTrue(cls.fields.last()[GenericAttributes.FIELD] == null)
        assertEquals(1000, cls.fields.size)
    }

    @Test fun sharedWideOuterScopeDoesNotChargeEveryEmptyChildButBoundsNewCopies() {
        val formals = (0 until 1500).joinToString("") { "T$it:Ljava/lang/Object;" }
        val outer = FakeClassData("LOuter;", nesting = com.jadxmp.input.ClassNesting.TopLevel,
            genericSignature = "<$formals>Ljava/lang/Object;")
        val sharedChildren = (0 until 1000).map { index -> FakeClassData("LPlain$index;",
            nesting = com.jadxmp.input.ClassNesting.Nested("LOuter;")) }
        val addingChildren = (0 until 500).map { index -> FakeClassData("LGeneric$index;",
            nesting = com.jadxmp.input.ClassNesting.Nested("LOuter;"),
            genericSignature = "<U:Ljava/lang/Object;>Ljava/lang/Object;") }
        val root = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(outer) + sharedChildren + addingChildren))
        assertTrue(root.classes.filter { it.fullName.startsWith("Plain") }.all { !it.contains(AttrFlag.HAS_ERROR) })
        val limited = root.classes.filter { it.fullName.startsWith("Generic") && it.contains(AttrFlag.HAS_ERROR) }
        assertTrue(limited.isNotEmpty())
        assertTrue(limited.all { it[IrAttrs.ERROR]!!.message.contains("aggregate generic scope storage limit") })
        assertTrue(limited.all { it[GenericAttributes.RECOVERIES] == null })
        assertEquals(1501, root.classes.size)
    }

    @Test fun validDeepErasureIsUnsupportedRatherThanMalformedRecovery() {
        val formals = (0..65).joinToString("") { index ->
            "T$index:" + if (index == 65) "Ljava/lang/Object;" else "TT${index + 1};"
        }
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(FakeClassData("LDeep;",
            genericSignature = "<$formals>Ljava/lang/Object;")))).classes.single()
        assertTrue(cls.contains(AttrFlag.HAS_ERROR))
        assertNull(cls[GenericAttributes.RECOVERIES])
    }

    @Test fun attachesValidatedMetadataAlongsideErasedIdentities() {
        val data = FakeClassData("LBox;", genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            fields = listOf(FakeFieldData(FakeFieldRef("LBox;", "value", "Ljava/lang/Object;"), genericSignature = "TT;")),
            methods = listOf(FakeMethodData(FakeMethodRef("LBox;", "echo", "Ljava/lang/Object;", listOf("Ljava/lang/Object;")),
                genericSignature = "(TT;)TT;")))
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(data))).classes.single()
        assertEquals("T", cls[GenericAttributes.CLASS]?.parameters?.single()?.name)
        assertEquals(IrType.OBJECT, cls.fields.single().type)
        assertEquals(IrType.typeVariable("T"), cls.fields.single()[GenericAttributes.FIELD])
        assertEquals(listOf(IrType.OBJECT), cls.methods.single().argTypes)
        assertEquals(IrType.OBJECT, cls.methods.single().returnType)
        assertEquals(IrType.typeVariable("T"), cls.methods.single()[GenericAttributes.METHOD]?.returnType)
    }

    @Test fun largeMismatchedHierarchyRecoversUniqueSignaturesWithoutRebindingDuplicates() {
        val signatureInterfaces = (0 until 1000).joinToString("") { "LI$it<Ljava/lang/String;>;" } + "LI0<Ljava/lang/Object;>;"
        val erasedInterfaces = (0 until 10_000).map { "LI$it;" }
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(FakeClassData("LManyInterfaces;",
            interfaces = erasedInterfaces, genericSignature = "Ljava/lang/Object;$signatureInterfaces")))).classes.single()
        val source = cls[GenericAttributes.CLASS]!!.interfaces
        assertEquals(10_000, source.size)
        assertEquals(IrType.objectType("I0"), source[0]) // ambiguous duplicate remains authoritative erased type
        assertEquals(IrType.generic("I1", IrType.STRING), source[1])
        assertEquals(IrType.objectType("I9999"), source.last())
        assertNull(cls[IrAttrs.ERROR])
        assertEquals(1, cls[GenericAttributes.RECOVERIES]!!.size)
    }

    @Test fun incompatibleClassSignatureKeepsHierarchyAndSiblingsWithDiagnostic() {
        val broken = FakeClassData("LBroken;", genericSignature = "<T:Ljava/lang/Object;>LBroken<TT;>;",
            methods = listOf(FakeMethodData(FakeMethodRef("LBroken;", "ok", "I", emptyList()))))
        val root = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(broken, FakeClassData("LGood;"))))
        val cls = root.findClass("Broken")!!
        assertEquals(IrType.OBJECT, cls.superType)
        assertEquals(IrType.OBJECT, cls[GenericAttributes.CLASS]?.superType)
        assertEquals("T", cls[GenericAttributes.CLASS]?.parameters?.single()?.name)
        assertNull(cls[IrAttrs.ERROR])
        assertTrue(cls[GenericAttributes.RECOVERIES]!!.single().reason.contains("erased hierarchy"))
        assertEquals("ok", cls.methods.single().name)
        assertNotNull(root.findClass("Good"))
    }

    @Test fun unverifiedConstructorOmissionsWaitForBodyProofInsteadOfClaimingCorruption() {
        val data = FakeClassData("LCapture;", methods = listOf(FakeMethodData(
            FakeMethodRef("LCapture;", "<init>", "V", listOf("Ljava/lang/Object;", "I")), genericSignature = "()V")))
        val method = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(data))).classes.single().methods.single()
        assertNull(method[GenericAttributes.METHOD])
        assertNull(method[GenericAttributes.RECOVERIES])
        assertNotNull(method[GenericAttributes.PENDING_CONSTRUCTOR])
    }

    @Test fun enumConstructorSignatureMayOmitOnlyItsVerifiedSyntheticPrefix() {
        val data = FakeClassData("LEnumCase;", superType = "Ljava/lang/Enum;", accessFlags = com.jadxmp.input.AccessFlags.ENUM,
            methods = listOf(FakeMethodData(FakeMethodRef("LEnumCase;", "<init>", "V", listOf("Ljava/lang/String;", "I")),
                genericSignature = "()V")))
        val method = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(data))).classes.single().methods.single()
        val signature = assertNotNull(method[GenericAttributes.METHOD])
        assertEquals(emptyList(), signature.argumentTypes)
        assertEquals(listOf(IrType.STRING, IrType.INT), signature.erasedPrefixTypes)
        assertNull(method[IrAttrs.ERROR])
    }

    @Test fun enclosingBoundsKeepTheirLexicalBindingUnderMethodShadowing() {
        val data = FakeClassData("LShadow;", genericSignature = "<T:Ljava/lang/Number;U:TT;>Ljava/lang/Object;",
            methods = listOf(FakeMethodData(FakeMethodRef("LShadow;", "id", "Ljava/lang/Number;", listOf("Ljava/lang/Number;")),
                genericSignature = "<T:Ljava/lang/String;>(TU;)TU;")))
        val method = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(data))).classes.single().methods.single()
        assertNotNull(method[GenericAttributes.METHOD])
        assertNull(method[IrAttrs.ERROR])
        assertEquals(IrType.objectType("java.lang.Number"), method.returnType)
    }

    @Test fun methodEnclosedClassCannotCaptureAnUnrelatedSameNamedOuterParameter() {
        val method = FakeMethodRef("LOuter;", "make", "V", emptyList())
        val outer = FakeClassData("LOuter;", nesting = com.jadxmp.input.ClassNesting.TopLevel,
            genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;")
        for (legacy in listOf(false, true)) {
            val inner = FakeClassData("LOuter\$Local;",
                nesting = if (legacy) null else com.jadxmp.input.ClassNesting.Nested("LOuter;", method),
                annotations = if (!legacy) emptyList() else listOf(com.jadxmp.input.AnnotationData(
                    "Ldalvik/annotation/EnclosingMethod;", com.jadxmp.input.AnnotationVisibility.SYSTEM,
                    mapOf("value" to com.jadxmp.input.EncodedValue(com.jadxmp.input.EncodedValueType.METHOD, method)))),
                fields = listOf(FakeFieldData(FakeFieldRef("LOuter\$Local;", "value", "Ljava/lang/Object;"), genericSignature = "TT;")))
            val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(outer, inner))).findClass("Outer\$Local")!!
            assertNull(cls.fields.single()[GenericAttributes.FIELD])
            assertTrue(cls.contains(AttrFlag.HAS_ERROR))
            assertNull(cls[GenericAttributes.RECOVERIES])
        }
    }

    @Test fun explicitEnclosureRequiresItsOwnerAndKeepsStaticRestrictionsSeparate() {
        val outer = FakeClassData("LOuter;", nesting = com.jadxmp.input.ClassNesting.TopLevel,
            genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;")
        for (ownerAvailable in listOf(false, true)) for (isStatic in listOf(false, true)) {
            val child = FakeClassData("LMember;", nesting = com.jadxmp.input.ClassNesting.Nested("LOuter;"),
                accessFlags = if (isStatic) com.jadxmp.input.AccessFlags.STATIC else 0,
                fields = listOf(FakeFieldData(FakeFieldRef("LMember;", "value", "Ljava/lang/Object;"), genericSignature = "TT;")))
            val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(child) + if (ownerAvailable) listOf(outer) else emptyList()))
                .findClass("Member")!!
            assertEquals(!ownerAvailable || isStatic, cls.contains(AttrFlag.HAS_ERROR))
            assertNull(cls[GenericAttributes.RECOVERIES])
            assertEquals(if (ownerAvailable && !isStatic) IrType.typeVariable("T") else null,
                cls.fields.single()[GenericAttributes.FIELD])
        }
    }

    @Test fun cancellationFromLazyMetadataIsNeverConvertedToAnError() {
        val input = object : ClassData by FakeClassData("LBox;") {
            override val genericSignature: String? get() = throw CancellationSignal()
        }
        assertFailsWith<CancellationSignal> { ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(input))) }
    }

    @Test fun unsupportedValidMetadataAndProviderFailuresRemainErrors() {
        val unsupported = FakeClassData("LBox;", genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            fields = listOf(FakeFieldData(FakeFieldRef("LBox;", "value", "LOuter\$Inner;"),
                genericSignature = "LOuter<TT;>.Inner;")))
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(unsupported))).classes.single()
        assertTrue(cls.contains(AttrFlag.HAS_ERROR))
        assertNull(cls[GenericAttributes.RECOVERIES])
        val broken = object : ClassData by FakeClassData("LBug;") {
            override val genericSignature: String? get() = error("provider bug")
        }
        val bug = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(broken))).classes.single()
        assertTrue(bug.contains(AttrFlag.HAS_ERROR))
        assertNull(bug[GenericAttributes.RECOVERIES])
    }

    @Test fun memberErasureMismatchIsIsolated() {
        val data = FakeClassData("LBox;", fields = listOf(
            FakeFieldData(FakeFieldRef("LBox;", "bad", "Ljava/lang/Object;"), genericSignature = "Ljava/lang/String;"),
            FakeFieldData(FakeFieldRef("LBox;", "good", "Ljava/util/List;"), genericSignature = "Ljava/util/List<Ljava/lang/String;>;")))
        val cls = ModelBuilder.buildDeclarations(FakeCodeLoader(listOf(data))).classes.single()
        assertNull(cls.fields.first()[GenericAttributes.FIELD])
        assertNull(cls.fields.first()[IrAttrs.ERROR])
        assertEquals("Ljava/lang/String;", cls.fields.first()[GenericAttributes.RECOVERIES]?.single()?.signature)
        assertNotNull(cls.fields.last()[GenericAttributes.FIELD])
    }
}
