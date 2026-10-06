package com.jadxmp.oracle

import com.jadxmp.input.ClassDeclarationData
import com.jadxmp.input.jvm.JvmInput
import com.jadxmp.ir.generics.GenericDeclarationIndex
import com.jadxmp.pipeline.model.ExternalDeclarationModel
import java.io.File
import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Explicit tool-side classpath ingestion; none of these JVM/filesystem operations enter the engine. */
internal data class ClasspathMetadataProfile(
    val index: GenericDeclarationIndex,
    val evidence: List<String>,
) {
    companion object {
        const val ID = "fixture-classpath-v1"
        private const val MAX_ARCHIVE_ENTRIES = 20_000
        private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 128 * 1024 * 1024

        fun load(classpath: List<File>): ClasspathMetadataProfile {
            require(classpath.size in 1..16) { "metadata profile requires 1..16 explicit JARs" }
            val declarations = mutableListOf<ClassDeclarationData>()
            val evidence = mutableListOf("metadata_profile=$ID; consumer=candidate-kotlin; reference-and-candidate-java=unchanged")
            var entries = 0
            var expanded = 0L
            var members = 0L
            var signatureCharacters = 0L
            for (artifact in classpath) {
                require(artifact.isFile && artifact.length() <= MAX_TOTAL_BYTES) { "invalid or oversized metadata JAR: ${artifact.name}" }
                // Hash exactly the bounded bytes parsed below, avoiding a path/contents race.
                val archive = artifact.inputStream().use { it.readNBytes(MAX_TOTAL_BYTES + 1) }
                require(archive.size <= MAX_TOTAL_BYTES) { "metadata artifact byte limit exceeded" }
                val hash = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
                evidence += "metadata_artifact=${artifact.name}; sha256=$hash; bytes=${archive.size}"
                ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++entries <= MAX_ARCHIVE_ENTRIES) { "metadata archive entry limit exceeded" }
                        // Also bound skipped resources: advancing a stream can otherwise inflate a bomb.
                        val bytes = zip.readNBytes(MAX_ENTRY_BYTES + 1)
                        require(bytes.size <= MAX_ENTRY_BYTES) { "metadata entry byte limit exceeded: ${entry.name}" }
                        expanded += bytes.size
                        require(expanded <= MAX_TOTAL_BYTES) { "metadata expanded byte limit exceeded" }
                        if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                        if (entry.name.startsWith("META-INF/versions/") || entry.name == "module-info.class") {
                            evidence += "metadata_skip=${artifact.name}!/${entry.name}; versioned/module entry outside base-class profile"
                            continue
                        }
                        try {
                            val declaration = JvmInput.readDeclaration(bytes)
                            members += declaration.fields.size + declaration.methods.size
                            signatureCharacters += (declaration.genericSignature?.length ?: 0) +
                                declaration.fields.sumOf { (it.genericSignature?.length ?: 0).toLong() } +
                                declaration.methods.sumOf { (it.genericSignature?.length ?: 0).toLong() }
                            declarations += declaration
                        } catch (failure: Exception) {
                            if (failure is java.util.concurrent.CancellationException || failure is InterruptedException ||
                                failure is com.jadxmp.pipeline.pass.CancellationSignal) throw failure
                            evidence += "metadata_ERROR=${artifact.name}!/${entry.name}: ${failure::class.java.name}: ${failure.message}"
                        }
                        require(members <= 200_000 && signatureCharacters <= 16_000_000) {
                            "metadata aggregate member/signature work limit exceeded"
                        }
                    }
                }
            }
            val result = ExternalDeclarationModel.build(declarations)
            evidence += result.diagnostics.map { "metadata_${it.kind}=${it.owner}: ${it.message}" }
            val admitted = declarations.count { result.index.findClass(it.type.removePrefix("L").removeSuffix(";").replace('/', '.')) != null }
            evidence += "metadata_declarations=${declarations.size}; admitted=$admitted; expanded_bytes=$expanded; archive_entries=$entries"
            return ClasspathMetadataProfile(result.index, evidence)
        }
    }
}
