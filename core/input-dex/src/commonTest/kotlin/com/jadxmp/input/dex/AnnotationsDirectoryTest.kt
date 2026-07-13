package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Crafts an `annotations_directory_item` → `annotation_set_item` → `annotation_item` chain after a
 * pool-backed DEX and drives [AnnotationsParser] over it.
 */
class AnnotationsDirectoryTest {

    private val b = DexBuilder()
    private val tAnn = b.addType("LAnn;")
    private val sValue = b.addString("value")
    private val base = b.build()

    // Layout appended after `base`:
    //   setOff:  annotation_set_item  (size=1, one offset -> itemOff)
    //   itemOff: annotation_item      (RUNTIME, LAnn; { value = 42 })
    //   dirOff:  annotations_directory_item (class_ann=setOff, 1 field annotation)
    private val setOff = base.size
    private val itemOff = setOff + 8
    private val itemBytes = buildBytes {
        u8(1) // visibility RUNTIME
        uleb(tAnn); uleb(1); uleb(sValue); u8(0x04); u8(0x2a) // int 42
    }
    private val dirOff = itemOff + itemBytes.size
    private val fieldIdx = 3

    private val dex: Dex = run {
        val extra = buildBytes {
            i32(1); i32(itemOff) // annotation_set_item
            raw(itemBytes) // annotation_item
            i32(setOff); i32(1); i32(0); i32(0) // directory header: class_ann, fields=1, methods=0, params=0
            i32(fieldIdx); i32(setOff) // field_annotation entry
        }
        Dex(base + extra, "t", 0)
    }

    @Test
    fun readsAnnotationSet() {
        val list = AnnotationsParser(dex).annotationList(setOff)
        assertEquals(1, list.size)
        val ann = list[0]
        assertEquals("LAnn;", ann.annotationType)
        assertEquals(AnnotationVisibility.RUNTIME, ann.visibility)
        assertEquals(EncodedValue(EncodedValueType.INT, 42), ann.values["value"])
    }

    @Test
    fun readsDirectoryOffsetMapsAndClassAnnotations() {
        val parser = AnnotationsParser(dex)
        parser.setDirectory(dirOff)
        assertEquals(mapOf(fieldIdx to setOff), parser.fieldAnnotationOffsets())
        assertEquals(emptyMap(), parser.methodAnnotationOffsets())

        val classAnns = parser.classAnnotations()
        assertEquals(1, classAnns.size)
        assertEquals("LAnn;", classAnns[0].annotationType)
    }
}
