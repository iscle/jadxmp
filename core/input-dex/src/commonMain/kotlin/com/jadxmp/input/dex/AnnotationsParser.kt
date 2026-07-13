package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.EncodedValue
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * Reads a class's `annotations_directory_item` and the annotation sets it points at.
 *
 * The directory is an indirection layer: it lists, per annotated field/method/parameter, the offset
 * of an `annotation_set_item`, which is itself a list of offsets to individual `annotation_item`s.
 * Call [setDirectory] once per class, then the offset-map/list accessors.
 *
 * jadx: AnnotationsParser
 */
internal class AnnotationsParser(private val dex: Dex) {
    private var directoryOff = 0
    private var fieldsCount = 0
    private var methodsCount = 0
    private var paramsCount = 0

    fun setDirectory(off: Int) {
        directoryOff = off
        if (off == 0) {
            fieldsCount = 0
            methodsCount = 0
            paramsCount = 0
            return
        }
        val c = dex.cursor(off + 4) // skip class_annotations_off
        fieldsCount = c.readS32()
        methodsCount = c.readS32()
        paramsCount = c.readS32()
    }

    fun classAnnotations(): List<AnnotationData> {
        if (directoryOff == 0) return emptyList()
        val classAnnOff = dex.cursor(directoryOff).readS32()
        return annotationList(classAnnOff)
    }

    /** Map of field index → annotation-set offset. */
    fun fieldAnnotationOffsets(): Map<Int, Int> {
        if (fieldsCount == 0) return emptyMap()
        return readIdxOffsetPairs(directoryOff + 16, fieldsCount)
    }

    /** Map of method index → annotation-set offset. */
    fun methodAnnotationOffsets(): Map<Int, Int> {
        if (methodsCount == 0) return emptyMap()
        return readIdxOffsetPairs(directoryOff + 16 + fieldsCount * 8, methodsCount)
    }

    /** Map of method index → annotation-set-ref-list offset (for parameter annotations). */
    fun parameterAnnotationRefOffsets(): Map<Int, Int> {
        if (paramsCount == 0) return emptyMap()
        return readIdxOffsetPairs(directoryOff + 16 + fieldsCount * 8 + methodsCount * 8, paramsCount)
    }

    private fun readIdxOffsetPairs(startOff: Int, count: Int): Map<Int, Int> {
        val c = dex.cursor(startOff)
        val map = HashMap<Int, Int>(Bounds.capacity(count, stride = 8, reader = c)) // (idx, off) pairs
        repeat(count) {
            val idx = c.readS32()
            val off = c.readS32()
            map[idx] = off
        }
        return map
    }

    /** An `annotation_set_item` → the annotations it contains. */
    fun annotationList(off: Int): List<AnnotationData> {
        if (off == 0) return emptyList()
        val c = dex.cursor(off)
        val size = c.readS32()
        if (size == 0) return emptyList()
        val list = ArrayList<AnnotationData>(Bounds.capacity(size, stride = 4, reader = c)) // offset entries
        for (i in 0 until size) {
            val annOff = dex.cursor(off + 4 + i * 4).readS32()
            list.add(readAnnotation(dex.cursor(annOff), readVisibility = true))
        }
        return list
    }

    /** An `annotation_set_ref_list` → one annotation list per parameter. */
    fun annotationRefList(off: Int): List<List<AnnotationData>> {
        if (off == 0) return emptyList()
        val c = dex.cursor(off)
        val size = c.readS32()
        if (size == 0) return emptyList()
        val list = ArrayList<List<AnnotationData>>(Bounds.capacity(size, stride = 4, reader = c))
        repeat(size) {
            val refOff = c.readS32()
            list.add(annotationList(refOff))
        }
        return list
    }

    fun readAnnotation(input: ByteReader, readVisibility: Boolean): AnnotationData {
        val visibility = if (readVisibility) visibilityOf(input.readU8()) else null
        val typeIdx = input.readUleb128().toInt()
        val size = input.readUleb128().toInt()
        // Each element is a uleb name index + an encoded value (>= 2 bytes); clamp preallocation.
        val values = LinkedHashMap<String, EncodedValue>(Bounds.capacity(size, stride = 2, reader = input))
        val valueParser = EncodedValueParser(dex)
        repeat(size) {
            val name = dex.string(input.readUleb128().toInt()) ?: "?"
            values[name] = valueParser.parseValue(input)
        }
        return AnnotationData(
            annotationType = dex.type(typeIdx) ?: "?",
            visibility = visibility,
            values = values,
        )
    }

    private fun visibilityOf(value: Int): AnnotationVisibility = when (value) {
        0 -> AnnotationVisibility.BUILD
        1 -> AnnotationVisibility.RUNTIME
        2 -> AnnotationVisibility.SYSTEM
        else -> throw ByteReaderException("unknown annotation visibility: $value")
    }
}
