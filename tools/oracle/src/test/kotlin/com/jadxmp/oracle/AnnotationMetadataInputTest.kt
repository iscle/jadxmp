package com.jadxmp.oracle

import com.jadxmp.input.dex.DexInput
import com.jadxmp.ir.annotation.AnnotationMetadata
import com.jadxmp.ir.annotation.IrAnnotation
import com.jadxmp.ir.annotation.IrAnnotationSet
import com.jadxmp.ir.annotation.IrAnnotationValue
import com.jadxmp.ir.annotation.IrAnnotationVisibility
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.model.ModelBuilder
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Input-to-IR evidence only: source emission still needs annotation declarations and use-site work. */
class AnnotationMetadataInputTest {
    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T, "Unexpected metadata: $value")
        return value as T
    }

    @Test fun javacDexDefaultsAndAllDeclarationAttachmentsReachTypedIr() {
        val source = DecompiledClass("fixtures.MetadataSample", """
            package fixtures;
            import java.lang.annotation.*;
            public class MetadataSample {
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Tag { int number() default -253; Class<?> type() default int[].class; }
                @Retention(RetentionPolicy.CLASS)
                public @interface Invisible {}
                @Tag @Invisible
                public static class Marked {
                    @Tag public String field;
                    @Tag public Marked() {}
                    @Tag(number=8) public int method(long wide, @Tag String value) { return 1; }
                }
            }
        """.trimIndent())
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(source), source.fullName))
        val input = DexInput.load("annotations.dex", dex)
        // The pinned D8 conversion strips CLASS-retained attachments. This is an input limitation,
        // not evidence that source emission preserves invisible classfile annotations.
        assertTrue(input.classes.single { it.type.endsWith("\$Marked;") }.annotations.none {
            it.annotationType.endsWith("\$Invisible;")
        })
        val root = ModelBuilder.build(input)
        val tag = root.findClass("fixtures.MetadataSample\$Tag")!!
        val number = tag.methods.single { it.name == "number" }
        assertEquals(AnnotationMetadata.Ready(IrAnnotationValue.Primitive(-253, IrType.INT)), number[SourceAttributes.ANNOTATION_DEFAULT])
        assertEquals(AnnotationMetadata.Ready(IrAnnotationValue.ClassLiteral(IrType.array(IrType.INT))), tag.methods.single { it.name == "type" }[SourceAttributes.ANNOTATION_DEFAULT])
        fun entries(metadata: AnnotationMetadata<IrAnnotationSet>?) =
            assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(metadata).value.entries.map { assertIs<AnnotationMetadata.Ready<IrAnnotation>>(it).value }
        val marked = root.findClass("fixtures.MetadataSample\$Marked")!!
        val annotations = entries(marked[SourceAttributes.ANNOTATIONS])
        assertEquals(IrAnnotationVisibility.RUNTIME, annotations.single { it.type.className.endsWith("\$Tag") }.visibility)
        assertTrue(annotations.none { it.type.className.endsWith("\$Invisible") })
        assertEquals(1, entries(marked.fields.single()[SourceAttributes.ANNOTATIONS]).size)
        assertEquals(1, entries(marked.methods.single { it.name == "<init>" }[SourceAttributes.ANNOTATIONS]).size)
        val method = marked.methods.single { it.name == "method" }
        assertEquals(IrAnnotationValue.Primitive(8, IrType.INT), entries(method[SourceAttributes.ANNOTATIONS]).single().values["number"])
        val parameters = assertIs<AnnotationMetadata.Ready<List<IrAnnotationSet>>>(method[SourceAttributes.PARAMETER_ANNOTATIONS]).value
        assertEquals(listOf(0, 1), parameters.map { it.entries.size })
    }
    @Test fun zeroMemberDefaultContainerRemainsInspectableBeforeLazyValidation() {
        val directory = Files.createTempDirectory("jadxmp-empty-annotation").toFile()
        try {
            val file = File(directory, "Empty.smali")
            file.writeText("""
                .class public interface abstract annotation Lfixtures/Empty;
                .super Ljava/lang/Object;
                .implements Ljava/lang/annotation/Annotation;
                .annotation system Ldalvik/annotation/AnnotationDefault;
                    value = .subannotation Lfixtures/Empty;
                        ghost = 0x7
                    .end subannotation
                .end annotation
            """.trimIndent())
            val dex = requireNotNull(SmaliAssembler.assemble(file).dex)
            val root = ModelBuilder.build(DexInput.load("empty.dex", dex))
            val cls = root.classes.single()
            assertTrue(cls.methods.isEmpty())
            val entries = assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(cls[SourceAttributes.ANNOTATIONS]).value.entries
            val wrapper = assertIs<AnnotationMetadata.Ready<IrAnnotation>>(entries.single()).value
            val nested = assertIs<IrAnnotationValue.Nested>(wrapper.values["value"])
            assertEquals(IrAnnotationValue.Primitive(7, IrType.INT), nested.annotation.values["ghost"])
            // No method exists to request the lazy default index. The raw wrapper remains available;
            // whole-class default validation is a documented remaining boundary, not a parity claim.
        } finally {
            directory.deleteRecursively()
        }
    }

}
