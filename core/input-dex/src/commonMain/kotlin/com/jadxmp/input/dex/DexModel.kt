package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.CatchHandler
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeReader
import com.jadxmp.input.DebugInfo
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.FieldData
import com.jadxmp.input.FieldRef
import com.jadxmp.input.LocalVar
import com.jadxmp.input.MethodData
import com.jadxmp.input.MethodHandle
import com.jadxmp.input.MethodHandleType
import com.jadxmp.input.MethodProto
import com.jadxmp.input.MethodRef
import com.jadxmp.input.TryBlock

// Concrete, immutable input-model holders produced by the DEX parser. They exist only to implement
// the core:input SPI; the engine sees them purely through those interfaces.

internal class DexFieldRef(
    override val declaringClassType: String,
    override val name: String,
    override val type: String,
) : FieldRef

internal class DexMethodProto(
    override val returnType: String,
    override val parameterTypes: List<String>,
) : MethodProto

internal class DexMethodRef(
    override val declaringClassType: String,
    override val name: String,
    override val returnType: String,
    override val parameterTypes: List<String>,
) : MethodRef

internal class DexMethodHandle(
    override val type: MethodHandleType,
    override val fieldRef: FieldRef?,
    override val methodRef: MethodRef?,
) : MethodHandle

internal class DexCallSite(
    override val values: List<EncodedValue>,
) : com.jadxmp.input.CallSite

internal class DexCatchHandler(
    override val types: List<String>,
    override val handlers: List<Int>,
    override val catchAllHandler: Int,
) : CatchHandler

internal class DexTryBlock(
    override val startOffset: Int,
    override val endOffset: Int,
    override val catchHandler: CatchHandler,
) : TryBlock

internal class DexLocalVar(
    override val registerNum: Int,
    override val name: String?,
    override val type: String?,
    override val signature: String?,
    override val startOffset: Int,
    override val endOffset: Int,
    override val isParameter: Boolean,
) : LocalVar

internal class DexDebugInfo(
    override val lineNumbers: Map<Int, Int>,
    override val localVars: List<LocalVar>,
) : DebugInfo

internal class DexFieldData(
    declaringClassType: String,
    name: String,
    type: String,
    override val accessFlags: Int,
    override val annotations: List<AnnotationData>,
    override val constValue: EncodedValue?,
) : FieldData {
    private val ref = DexFieldRef(declaringClassType, name, type)
    override val declaringClassType: String get() = ref.declaringClassType
    override val name: String get() = ref.name
    override val type: String get() = ref.type
}

internal class DexMethodData(
    override val ref: MethodRef,
    override val accessFlags: Int,
    override val annotations: List<AnnotationData>,
    override val parameterAnnotations: List<List<AnnotationData>>,
    private val codeProvider: () -> CodeReader?,
) : MethodData {
    override val codeReader: CodeReader? by lazy(codeProvider)
}

internal class DexClassData(
    override val type: String,
    override val accessFlags: Int,
    override val superType: String?,
    override val interfaces: List<String>,
    override val sourceFile: String?,
    override val fields: List<FieldData>,
    override val methods: List<MethodData>,
    override val annotations: List<AnnotationData>,
    override val inputFileName: String,
) : ClassData {
    override fun disassemble(): String = SmaliPrinter.render(this)
}
