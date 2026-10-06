package com.jadxmp.pipeline.model

import com.jadxmp.input.AccessFlags
import com.jadxmp.input.AnnotationData
import com.jadxmp.input.ClassData
import com.jadxmp.input.ClassDeclarationData
import com.jadxmp.input.CodeReader
import com.jadxmp.input.FieldData
import com.jadxmp.input.ListCodeLoader
import com.jadxmp.input.MethodData
import com.jadxmp.input.MethodRef
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.generics.GenericAttributes
import com.jadxmp.ir.generics.GenericClassDeclaration
import com.jadxmp.ir.generics.GenericDeclarationIndex
import com.jadxmp.ir.generics.GenericFieldDeclaration
import com.jadxmp.ir.generics.GenericMethodDeclaration

/** Invalid optional metadata can recover; unsupported metadata must remain distinguishable. */
enum class ExternalDeclarationDiagnosticKind { RECOVERY, ERROR }
data class ExternalDeclarationDiagnostic(val owner: String, val kind: ExternalDeclarationDiagnosticKind, val message: String)
data class ExternalDeclarationResult(val index: GenericDeclarationIndex, val diagnostics: List<ExternalDeclarationDiagnostic>)

/** Validates classpath source metadata using the same lexical scopes and erasures as program input. */
object ExternalDeclarationModel {
    fun build(declarations: List<ClassDeclarationData>): ExternalDeclarationResult {
        require(declarations.map { it.type }.toSet().size == declarations.size) { "duplicate external class declaration" }
        for (cls in declarations) {
            require(cls.fields.map { it.name to it.type }.toSet().size == cls.fields.size) { "duplicate external field declaration in ${cls.type}" }
            require(cls.methods.map { Triple(it.name, it.parameterTypes, it.returnType) }.toSet().size == cls.methods.size) {
                "duplicate external method declaration in ${cls.type}"
            }
        }
        val root = ModelBuilder.buildDeclarations(ListCodeLoader(declarations.map(::metadataInput)))
        val diagnostics = mutableListOf<ExternalDeclarationDiagnostic>()
        fun report(owner: String, node: AttrNode) {
            node[IrAttrs.ERROR]?.let { diagnostics += ExternalDeclarationDiagnostic(owner, ExternalDeclarationDiagnosticKind.ERROR, it.message) }
            if (node[GenericAttributes.PENDING_CONSTRUCTOR] != null) {
                diagnostics += ExternalDeclarationDiagnostic(owner, ExternalDeclarationDiagnosticKind.ERROR,
                    "constructor signature requires executable synthetic-parameter proof; classpath bodies are not loaded")
            }
            node[GenericAttributes.RECOVERIES]?.forEach {
                diagnostics += ExternalDeclarationDiagnostic(owner, ExternalDeclarationDiagnosticKind.RECOVERY, it.reason)
            }
        }
        val classes = root.classes.mapNotNull { cls ->
            val start = diagnostics.size
            var enclosing = cls
            while (enclosing.accessFlags and AccessFlags.STATIC == 0) {
                enclosing = enclosing.outerClass ?: break
                if (!enclosing[GenericAttributes.CLASS]?.parameters.isNullOrEmpty()) {
                    diagnostics += ExternalDeclarationDiagnostic(cls.fullName, ExternalDeclarationDiagnosticKind.ERROR,
                        "external catalog cannot yet represent enclosing generic scope: ${enclosing.fullName}")
                    break
                }
            }
            report(cls.fullName, cls)
            cls.fields.forEach { report("${cls.fullName}.${it.name}", it) }
            cls.methods.forEach { report("${cls.fullName}.${it.name}", it) }
            // A partial unsupported scope is not authoritative library metadata. The caller receives
            // an explicit error and can decide how to surface missing declarations to its users.
            if (diagnostics.drop(start).any { it.kind == ExternalDeclarationDiagnosticKind.ERROR }) return@mapNotNull null
            val signature = cls[GenericAttributes.CLASS]
            GenericClassDeclaration(
                cls.fullName, signature?.parameters ?: emptyList(), signature?.superType ?: cls.superType,
                signature?.interfaces ?: cls.interfaces,
                cls.methods.map { GenericMethodDeclaration(it.name, it.argTypes, it.returnType, it.accessFlags, it[GenericAttributes.METHOD]) },
                cls.fields.map { GenericFieldDeclaration(it.name, it.type, it.accessFlags, it[GenericAttributes.FIELD]) },
                cls.accessFlags,
            )
        }
        return ExternalDeclarationResult(GenericDeclarationIndex(classes), diagnostics)
    }

    private fun metadataInput(data: ClassDeclarationData): ClassData = object : ClassData {
        override val type = data.type
        override val accessFlags = data.accessFlags
        override val superType = data.superType
        override val interfaces = data.interfaces
        override val genericSignature = data.genericSignature
        override val nesting = data.nesting
        override val innerAccessFlags = data.innerAccessFlags
        override val sourceFile: String? = null
        override val annotations = emptyList<AnnotationData>()
        override val inputFileName = "<external declaration>"
        override fun disassemble(): String = error("External declarations have no executable code")
        override val fields: List<FieldData> = data.fields.map { field ->
            object : FieldData {
                override val declaringClassType = data.type
                override val name = field.name
                override val type = field.type
                override val accessFlags = field.accessFlags
                override val genericSignature = field.genericSignature
                override val annotations = emptyList<AnnotationData>()
                override val constValue = null
            }
        }
        override val methods: List<MethodData> = data.methods.map { method ->
            object : MethodData {
                override val ref = object : MethodRef {
                    override val declaringClassType = data.type
                    override val name = method.name
                    override val parameterTypes = method.parameterTypes
                    override val returnType = method.returnType
                }
                override val accessFlags = method.accessFlags
                override val genericSignature = method.genericSignature
                override val annotations = emptyList<AnnotationData>()
                override val parameterAnnotations = emptyList<List<AnnotationData>>()
                override val codeReader: CodeReader? get() = error("External declarations have no executable code")
            }
        }
    }
}
