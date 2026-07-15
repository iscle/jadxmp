package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeReader
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.FieldData
import com.jadxmp.input.MethodData
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * Walks a DEX's `class_def` table and, for each class, its `class_data_item`, producing the normalized
 * [ClassData]/[FieldData]/[MethodData] model.
 *
 * Field and method indices in `class_data_item` are stored as cumulative deltas (each restarting at 0
 * for the static/instance and direct/virtual sub-lists), which is why the readers thread a running
 * index. Method bodies are wired up lazily via a [DexCodeReader] factory so a class can be structurally
 * parsed without decoding any instructions.
 *
 * jadx: DexClassData
 */
internal class DexClassParser(private val dex: Dex) {

    private val diagnosticsList = ArrayList<String>()

    /**
     * Non-fatal problems hit while walking the class table: each entry is a class that was skipped
     * because parsing it threw. Recorded rather than swallowed so the loss is never silent
     * (CLAUDE.md rule 4); exposed for tests and ready for the loader to surface.
     */
    val diagnostics: List<String> get() = diagnosticsList

    fun parseAll(): List<ClassData> {
        val count = dex.header.classDefsSize
        val classDefsOff = dex.header.classDefsOff
        val list = ArrayList<ClassData>(count)
        for (i in 0 until count) {
            // Per-class fault isolation, mirroring ArscDecoder's per-chunk salvage. Without this, one
            // corrupt class_def (bad type_idx / class_data_off, or a hostile constant/annotation blob
            // that exhausts memory or the stack) throws all the way up through Decompiler.load and
            // drops EVERY class in the DEX — a rule-4 violation. Catch Throwable, not just
            // ByteReaderException, so even an OutOfMemoryError / StackOverflowError from a single
            // poison class is contained and the rest of the container still loads.
            try {
                list.add(parseClass(classDefsOff + i * CLASS_DEF_SIZE))
            } catch (e: Throwable) {
                diagnosticsList += "class_def #$i skipped: ${e.message ?: "unrecoverable parse error"}"
            }
        }
        return list
    }

    private fun parseClass(defOffset: Int): DexClassData {
        val c = dex.cursor(defOffset)
        val classIdx = c.readS32()
        val accessFlags = c.readS32()
        val superIdx = c.readS32()
        val interfacesOff = c.readS32()
        val sourceFileIdx = c.readS32()
        val annotationsOff = c.readS32()
        val classDataOff = c.readS32()
        val staticValuesOff = c.readS32()

        val type = dex.type(classIdx) ?: throw ByteReaderException("class #$classIdx has no type")
        val superType = dex.type(superIdx)
        val interfaces = dex.typeList(interfacesOff)
        val sourceFile = dex.string(sourceFileIdx)

        val annotations = AnnotationsParser(dex)
        annotations.setDirectory(annotationsOff)
        val classAnnotations = annotations.classAnnotations()
        val fieldAnnOffsets = annotations.fieldAnnotationOffsets()
        val methodAnnOffsets = annotations.methodAnnotationOffsets()
        val paramAnnOffsets = annotations.parameterAnnotationRefOffsets()

        val fields = ArrayList<FieldData>()
        val methods = ArrayList<MethodData>()

        if (classDataOff != 0) {
            val data = dex.cursor(classDataOff)
            val staticFieldsSize = data.readUleb128().toInt()
            val instanceFieldsSize = data.readUleb128().toInt()
            val directMethodsSize = data.readUleb128().toInt()
            val virtualMethodsSize = data.readUleb128().toInt()

            val staticInit = if (staticValuesOff != 0) {
                EncodedValueParser(dex).parseArray(dex.cursor(staticValuesOff))
            } else {
                emptyList()
            }

            readFields(data, type, staticFieldsSize, fieldAnnOffsets, annotations, staticInit, fields)
            readFields(data, type, instanceFieldsSize, fieldAnnOffsets, annotations, emptyList(), fields)
            readMethods(data, directMethodsSize, methodAnnOffsets, paramAnnOffsets, annotations, methods)
            readMethods(data, virtualMethodsSize, methodAnnOffsets, paramAnnOffsets, annotations, methods)
        }

        return DexClassData(
            type = type,
            accessFlags = accessFlags,
            superType = superType,
            interfaces = interfaces,
            sourceFile = sourceFile,
            fields = fields,
            methods = methods,
            annotations = classAnnotations,
            inputFileName = dex.fileName,
        )
    }

    private fun readFields(
        data: ByteReader,
        classType: String,
        count: Int,
        annOffsets: Map<Int, Int>,
        annotations: AnnotationsParser,
        initValues: List<EncodedValue>,
        out: MutableList<FieldData>,
    ) {
        var fieldId = 0
        for (i in 0 until count) {
            fieldId += data.readUleb128().toInt()
            val accessFlags = data.readUleb128().toInt()
            val ref = dex.fieldRef(fieldId)
            val fieldAnnotations = annOffsets[fieldId]?.let { annotations.annotationList(it) } ?: emptyList()
            out.add(
                DexFieldData(
                    declaringClassType = classType,
                    name = ref.name,
                    type = ref.type,
                    accessFlags = accessFlags,
                    annotations = fieldAnnotations,
                    constValue = initValues.getOrNull(i),
                ),
            )
        }
    }

    private fun readMethods(
        data: ByteReader,
        count: Int,
        annOffsets: Map<Int, Int>,
        paramAnnOffsets: Map<Int, Int>,
        annotations: AnnotationsParser,
        out: MutableList<MethodData>,
    ) {
        var methodId = 0
        for (i in 0 until count) {
            methodId += data.readUleb128().toInt()
            val accessFlags = data.readUleb128().toInt()
            val codeOff = data.readUleb128().toInt()
            val ref = dex.methodRef(methodId)
            val methodAnnotations = annOffsets[methodId]?.let { annotations.annotationList(it) } ?: emptyList()
            val parameterAnnotations: List<List<AnnotationData>> =
                paramAnnOffsets[methodId]?.let { annotations.annotationRefList(it) } ?: emptyList()

            val capturedMethodId = methodId
            val provider: () -> CodeReader? =
                if (codeOff == 0) { { null } } else { { DexCodeReader(dex, codeOff, capturedMethodId) } }
            out.add(DexMethodData(ref, accessFlags, methodAnnotations, parameterAnnotations, provider))
        }
    }

    private companion object {
        const val CLASS_DEF_SIZE = 8 * 4
    }
}
