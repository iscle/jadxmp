package com.jadxmp.pipeline.support

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.ClassData
import com.jadxmp.input.ClassNesting
import com.jadxmp.input.CodeLoader
import com.jadxmp.input.CodeReader
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.FieldData
import com.jadxmp.input.MethodData
import com.jadxmp.input.MethodRef

/** Input-model test doubles for driving [com.jadxmp.pipeline.model.ModelBuilder] end-to-end. */

class FakeMethodData(
    override val ref: MethodRef,
    override val accessFlags: Int = 0,
    override val genericSignature: String? = null,
    override val codeReader: CodeReader? = null,
    override val annotations: List<AnnotationData> = emptyList(),
    override val parameterAnnotations: List<List<AnnotationData>> = emptyList(),
    override val annotationDefault: EncodedValue? = null,
) : MethodData

class FakeFieldData(
    private val fieldRef: FakeFieldRef,
    override val accessFlags: Int = 0,
    override val genericSignature: String? = null,
    override val constValue: EncodedValue? = null,
    override val annotations: List<AnnotationData> = emptyList(),
) : FieldData {
    override val declaringClassType: String get() = fieldRef.declaringClassType
    override val name: String get() = fieldRef.name
    override val type: String get() = fieldRef.type
}

class FakeClassData(
    override val type: String,
    override val superType: String? = "Ljava/lang/Object;",
    override val interfaces: List<String> = emptyList(),
    override val methods: List<MethodData> = emptyList(),
    override val fields: List<FieldData> = emptyList(),
    override val accessFlags: Int = 0,
    override val genericSignature: String? = null,
    override val annotations: List<AnnotationData> = emptyList(),
    override val nesting: ClassNesting? = null,
    override val reflectiveNesting: ClassNesting? = nesting,
    override val innerAccessFlags: Int? = null,
) : ClassData {
    override val sourceFile: String? get() = null
    override val inputFileName: String get() = "test"
    override fun disassemble(): String = ""
}

class FakeCodeLoader(override val classes: List<ClassData>) : CodeLoader
