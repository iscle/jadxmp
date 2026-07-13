package com.jadxmp.resources

import com.jadxmp.resources.android.AndroidResourceMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidResourceMapTest {

    @Test
    fun resolvesKnownFrameworkAttrs() {
        assertEquals("attr/theme", AndroidResourceMap.resName(0x01010000))
        assertEquals("attr/label", AndroidResourceMap.resName(0x01010001))
        assertEquals("attr/name", AndroidResourceMap.resName(0x01010003))
    }

    @Test
    fun resolvesIdAndStyleTypes() {
        // the bundled subset includes id/* and style/* entries too
        assertEquals("id/background", AndroidResourceMap.resName(0x01020000))
        assertEquals("id/checkbox", AndroidResourceMap.resName(0x01020001))
    }

    @Test
    fun unknownIdReturnsNull() {
        assertNull(AndroidResourceMap.resName(0x7f123456))
        assertNull(AndroidResourceMap.resName(0))
    }

    @Test
    fun bundlesSubstantialMap() {
        assertTrue(AndroidResourceMap.size > 4000, "expected a few thousand entries, got ${AndroidResourceMap.size}")
    }
}
