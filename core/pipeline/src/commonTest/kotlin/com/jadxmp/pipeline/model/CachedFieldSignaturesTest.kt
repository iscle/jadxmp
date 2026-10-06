package com.jadxmp.pipeline.model

import com.jadxmp.ir.generics.TypeParameter
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class CachedFieldSignaturesTest {
    @Test fun repeatedMalformedSignaturesReuseParsingWithoutLosingTheFailure() {
        val signatures = CachedFieldSignatures(emptyList())
        val first = assertFailsWith<InvalidGenericSignature> { signatures.parse("I") }
        repeat(20_000) {
            assertSame(first, assertFailsWith<InvalidGenericSignature> { signatures.parse("I") })
        }
        assertEquals("expected reference at signature offset 0", first.message)
        assertSame(signatures.parse("Ljava/util/List<*>;"), signatures.parse("Ljava/util/List<*>;"))
    }

    @Test fun entryAndTextStorageStayBounded() {
        val entries = CachedFieldSignatures(emptyList())
        repeat(128) { assertFailsWith<InvalidGenericSignature> { entries.parse("!$it") } }
        val overflow = assertFailsWith<InvalidGenericSignature> { entries.parse("!overflow") }
        assertNotSame(overflow, assertFailsWith<InvalidGenericSignature> { entries.parse("!overflow") })

        val text = CachedFieldSignatures(emptyList())
        val large = "!".repeat(32_768)
        val first = assertFailsWith<InvalidGenericSignature> { text.parse(large) }
        assertSame(first, assertFailsWith<InvalidGenericSignature> { text.parse(large) })
        val another = "?".repeat(32_768)
        val next = assertFailsWith<InvalidGenericSignature> { text.parse(another) }
        assertNotSame(next, assertFailsWith<InvalidGenericSignature> { text.parse(another) })
    }

    @Test fun scopeIsPerParserAndUnsupportedErrorsAreNotCached() {
        val scoped = CachedFieldSignatures(listOf(TypeParameter("T", IrType.STRING, emptyList())))
        assertEquals(IrType.typeVariable("T"), scoped.parse("TT;"))
        val unscoped = CachedFieldSignatures(emptyList())
        val missing = assertFailsWith<UnboundGenericVariable> { unscoped.parse("TT;") }
        assertNotSame(missing, assertFailsWith<UnboundGenericVariable> { unscoped.parse("TT;") })
        val tooLarge = "L" + "x".repeat(65_535) + ";"
        val unsupported = assertFailsWith<UnsupportedGenericSignature> { scoped.parse(tooLarge) }
        assertNotSame(unsupported, assertFailsWith<UnsupportedGenericSignature> { scoped.parse(tooLarge) })
    }
}
