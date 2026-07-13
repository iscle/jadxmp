package com.jadxmp.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArscDecoderTest {

    private val table = ArscDecoder.decode(Fixtures.ARSC)

    @Test
    fun parsesPackageMetadata() {
        assertTrue(table.diagnostics.isEmpty(), "unexpected diagnostics: ${table.diagnostics}")
        assertEquals(1, table.packages.size)
        val pkg = table.packages.single()
        assertEquals(0x7f, pkg.id)
        assertEquals("com.github.skylot.simple", pkg.name)
    }

    @Test
    fun resolvesSimpleStringValue() {
        // string/app_name lives at typeId 4, entry 0 -> 0x7f040000
        assertEquals("string/app_name", table.symbolicName(0x7f040000))
        val appName = table.entriesByName("string", "app_name")
        assertEquals(1, appName.size)
        assertEquals("Simple", table.formatEntry(appName.single()))
    }

    @Test
    fun resolvesLayoutFilePathValue() {
        val layout = table.entriesByName("layout", "activity_main").single()
        assertEquals("res/layout/activity_main.xml", table.formatEntry(layout))
    }

    @Test
    fun parsesComplexStyleBag() {
        val theme = table.entriesByName("style", "AppTheme").single()
        // A style is a complex/bag entry even when (as here) it declares no parent and no items.
        assertTrue(theme.isComplex, "AppTheme should be a complex/bag entry")
        assertEquals("[]", table.formatEntry(theme))
    }

    @Test
    fun parsesColorEntries() {
        // exact #rrggbb (TYPE_INT_COLOR_RGB8) values from the fixture
        val expected = mapOf(
            "colorAccent" to "#d81b60",
            "colorPrimary" to "#008577",
            "colorPrimaryDark" to "#00574b",
        )
        for ((name, color) in expected) {
            val e = table.entriesByName("color", name).single()
            assertEquals(color, table.formatEntry(e))
        }
    }

    @Test
    fun resolvesFrameworkIdViaBundledMap() {
        assertEquals("android:attr/theme", table.symbolicName(0x01010000))
    }

    @Test
    fun unknownIdResolvesToNull() {
        assertEquals(null, table.symbolicName(0x7f990000))
    }

    @Test
    fun malformedInputDoesNotCrash() {
        // random bytes
        val garbage = ByteArray(200) { (it * 31).toByte() }
        val t1 = ArscDecoder.decode(garbage)
        assertTrue(t1.packages.isEmpty())
        assertTrue(t1.diagnostics.isNotEmpty())

        // Truncated real table (table size patched to the new length so the chunk loop runs): the
        // global string pool is consumed, then the package chunk overruns the buffer and is skipped
        // with a diagnostic instead of being parsed or throwing.
        val truncated = Fixtures.ARSC.copyOfRange(0, 700)
        val n = truncated.size
        truncated[4] = (n and 0xff).toByte()
        truncated[5] = ((n shr 8) and 0xff).toByte()
        truncated[6] = 0
        truncated[7] = 0
        val t2 = ArscDecoder.decode(truncated)
        // Global pool was salvaged before the truncated package failed; the package is dropped with a
        // diagnostic and no exception escapes.
        assertNotNull(t2.globalStrings, "global string pool should have been salvaged")
        assertTrue(t2.packages.isEmpty())
        assertTrue(t2.diagnostics.isNotEmpty(), "expected a diagnostic for the truncated package")

        // empty input: no packages, no crash
        assertTrue(ArscDecoder.decode(ByteArray(0)).packages.isEmpty())
    }

    @Test
    fun decodesNormalOffsetTable() = assertBuiltEntryDecodes(ArscFixtureBuilder.Encoding.NORMAL)

    @Test
    fun decodesSparseOffsetTable() = assertBuiltEntryDecodes(ArscFixtureBuilder.Encoding.SPARSE)

    @Test
    fun decodesOffset16Table() = assertBuiltEntryDecodes(ArscFixtureBuilder.Encoding.OFFSET16)

    @Test
    fun decodesCompactEntry() = assertBuiltEntryDecodes(ArscFixtureBuilder.Encoding.COMPACT)

    /** Each crafted encoding must yield the same t/e0 = 42 entry at id 0x7f010000. */
    private fun assertBuiltEntryDecodes(encoding: ArscFixtureBuilder.Encoding) {
        val t = ArscDecoder.decode(ArscFixtureBuilder.build(encoding))
        assertTrue(t.diagnostics.isEmpty(), "$encoding produced diagnostics: ${t.diagnostics}")
        assertEquals("t/e0", t.symbolicName(0x7f010000))
        val entry = t.entriesByName("t", "e0").single()
        assertEquals(0x7f010000, entry.id)
        assertEquals("42", t.formatEntry(entry))
    }

    @Test
    fun hostileEntryCountIsBoundedNotOom() {
        // Craft a minimal TABLE with a package whose type chunk claims a billion entries.
        // requireAvailable must reject it as a diagnostic, not allocate gigabytes.
        val bytes = buildHostileTypeChunk()
        val table = ArscDecoder.decode(bytes)
        // No crash / OOM; the bad type chunk is skipped.
        assertTrue(table.diagnostics.isNotEmpty() || table.packages.all { it.entries.isEmpty() })
    }

    private fun buildHostileTypeChunk(): ByteArray {
        // Reuse the real table but overwrite the first type chunk's entryCount with 0x3fffffff.
        val bytes = Fixtures.ARSC.copyOf()
        // Locate the first TABLE_TYPE (0x0201) chunk by scanning 16-bit type ids.
        var i = 12
        while (i + 4 <= bytes.size) {
            val type = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            if (type == ResChunkTypes.TABLE_TYPE) {
                // entryCount is at chunkStart + 12 (u32)
                val ec = i + 12
                bytes[ec] = 0xff.toByte(); bytes[ec + 1] = 0xff.toByte()
                bytes[ec + 2] = 0xff.toByte(); bytes[ec + 3] = 0x3f.toByte()
                break
            }
            i += 2
        }
        return bytes
    }
}
