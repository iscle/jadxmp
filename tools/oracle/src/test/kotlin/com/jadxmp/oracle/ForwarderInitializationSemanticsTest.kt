package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForwarderInitializationSemanticsTest {
    @Test fun javaForwarderRetainsOwnerSuperclassAndDefaultInterfaceInitialization() = verify(false)
    @Test fun kotlinForwarderRetainsOwnerSuperclassAndDefaultInterfaceInitialization() = verify(true)

    @Test fun javaPrivateAccessorRemainsCallableFromAnotherClass() = verify(false, privateTarget = true)
    @Test fun kotlinPrivateAccessorRemainsCallableFromAnotherClass() = verify(true, privateTarget = true)

    @Test fun javaInheritedSymbolicCallStillFindsItsForwarder() = verify(false, inheritedCaller = true)
    @Test fun kotlinInheritedSymbolicCallStillFindsItsForwarder() = verify(true, inheritedCaller = true)

    private fun verify(kotlin: Boolean, privateTarget: Boolean = false, inheritedCaller: Boolean = false) {
        val trace = DecompiledClass("initialization.Trace", """
            package initialization;
            public class Trace {
                public static String events = "";
                public static String fail = "";
                public static int record(String event) {
                    events += event;
                    if (fail.equals(event)) throw new IllegalStateException(event);
                    return 1;
                }
            }
        """.trimIndent())
        // Keep the Java default interface external: this isolates the inliner from declaration
        // reconstruction while still requiring JVM initialization of an unknown ancestor interface.
        val defaults = source("InitDefaults", "public interface InitDefaults { int marker = Trace.record(\"D\"); default void touch() {} }")
        val sameOwner = privateTarget || inheritedCaller
        val callerOwner = if (inheritedCaller) "InitChild" else "InitBridge"
        val targetCall = if (sameOwner) "hidden()" else "InitTarget.value()"
        val hiddenMethod = if (sameOwner) "${if (privateTarget) "private" else "public"} static int hidden() { return 7; }" else ""
        val originals = listOf(
            source("InitParent", "public class InitParent { static { Trace.record(\"P\"); } }"),
            source("InitBridge", "public class InitBridge extends InitParent implements InitDefaults { static { Trace.record(\"B\"); } public static int bridge() { return $targetCall; } $hiddenMethod }"),
            source("InitTarget", "public class InitTarget { static { Trace.record(\"T\"); } public static int value() { return 7; } }"),
            source("InitCaller", "public class InitCaller { public static int call() { return $callerOwner.bridge(); } }"),
        )
        val completeOriginals = originals + if (inheritedCaller) listOf(source("InitChild", "public class InitChild extends InitBridge {}")) else emptyList()
        val engine = Decompiler(DecompilerArgs(outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA))
        assertEquals(if (inheritedCaller) 5 else 4, engine.load("forwarder-initialization.zip", fixture(privateTarget, inheritedCaller)))
        val result = engine.decompileAll()
        assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
        val generated = result.classes.map { DecompiledClass(it.fullName, it.code) }
        JavaCompilation.compile(listOf(trace, defaults)).use { helper ->
            assertTrue(helper.result.success, helper.result.diagnostics.toString())
            JavaCompilation.compile(completeOriginals, listOf(helper.output)).use { original ->
                assertTrue(original.result.success, original.result.diagnostics.toString())
                // Each case needs fresh initialization state in a fresh class loader.
                val order = if (sameOwner) "PDB" else "PDBT"
                for (failure in listOf("") + order.map { it.toString() }) {
                    val expected = URLClassLoader(arrayOf(original.output.toURI().toURL(), helper.output.toURI().toURL())).use {
                        observe(it, kotlin = false, failure)
                    }
                    val prefix = if (failure.isEmpty()) order else order.substringBefore(failure) + failure
                    assertEquals(prefix, expected.events)
                    assertEquals(if (failure.isEmpty()) 7 else null, expected.value)
                    assertEquals(if (failure.isEmpty()) null else "java.lang.ExceptionInInitializerError", expected.firstFailure)
                    assertEquals(if (failure.isEmpty()) null else "java.lang.NoClassDefFoundError", expected.secondFailure)
                    withCompiledClasses(generated, kotlin, listOf(helper.output)) { loader ->
                        assertEquals(expected, observe(loader, kotlin, failure), "failure=$failure\n${generated.joinToString { it.source }}")
                    }
                }
            }
        }
    }

    private data class Observation(val value: Int?, val events: String, val firstFailure: String?, val secondFailure: String?)

    private fun observe(loader: ClassLoader, kotlin: Boolean, failure: String): Observation {
        val trace = loader.loadClass("initialization.Trace")
        trace.getField("fail").set(null, failure)
        val caller = loader.loadClass("initialization.InitCaller")
        val target = if (kotlin) caller.getField("Companion").get(null) else null
        val call = (target?.javaClass ?: caller).getMethod("call")
        fun invoke(): Pair<Int?, String?> = try {
            (call.invoke(target) as Int) to null
        } catch (error: InvocationTargetException) {
            null to error.cause!!.javaClass.name
        }
        val first = invoke()
        val second = invoke()
        return Observation(first.first, trace.getField("events").get(null) as String, first.second, second.second)
    }

    private fun source(name: String, declaration: String) = DecompiledClass("initialization.$name", "package initialization;\n$declaration")

    private fun fixture(privateTarget: Boolean, inheritedCaller: Boolean): ByteArray {
        fun clinit(event: String) = """
            .method static constructor <clinit>()V
                .registers 1
                const-string v0, "$event"
                invoke-static {v0}, Linitialization/Trace;->record(Ljava/lang/String;)I
                return-void
            .end method
        """.trimIndent()
        val sameOwner = privateTarget || inheritedCaller
        val callerOwner = if (inheritedCaller) "InitChild" else "InitBridge"
        val invokeTarget = if (sameOwner) "InitBridge;->hidden" else "InitTarget;->value"
        val hidden = if (sameOwner) """
            .method ${if (privateTarget) "private" else "public"} static hidden()I
                .registers 1
                const/4 v0, 0x7
                return v0
            .end method
        """.trimIndent() else ""
        val files = mapOf(
            "InitParent" to ".class public Linitialization/InitParent;\n.super Ljava/lang/Object;\n${clinit("P")}",
            "InitBridge" to """
                .class public Linitialization/InitBridge;
                .super Linitialization/InitParent;
                .implements Linitialization/InitDefaults;
                ${clinit("B")}
                .method public static synthetic bridge()I
                    .registers 1
                    invoke-static {}, Linitialization/$invokeTarget()I
                    move-result v0
                    return v0
                .end method
                $hidden
            """.trimIndent(),
            "InitTarget" to """
                .class public Linitialization/InitTarget;
                .super Ljava/lang/Object;
                ${clinit("T")}
                .method public static value()I
                    .registers 1
                    const/4 v0, 0x7
                    return v0
                .end method
            """.trimIndent(),
            "InitCaller" to """
                .class public Linitialization/InitCaller;
                .super Ljava/lang/Object;
                .method public static call()I
                    .registers 1
                    invoke-static {}, Linitialization/$callerOwner;->bridge()I
                    move-result v0
                    return v0
                .end method
            """.trimIndent(),
        )
        val completeFiles = files + if (inheritedCaller) mapOf("InitChild" to ".class public Linitialization/InitChild;\n.super Linitialization/InitBridge;") else emptyMap()
        val directory = Files.createTempDirectory("jadxmp-forwarder-init").toFile()
        try {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                for ((index, entry) in completeFiles.entries.withIndex()) {
                    val file = directory.resolve(entry.key + ".smali").apply { writeText(entry.value) }
                    val assembled = SmaliAssembler.assemble(file)
                    assertTrue(assembled.ok, assembled.error)
                    zip.putNextEntry(ZipEntry("classes${if (index == 0) "" else index + 1}.dex"))
                    zip.write(assembled.dex!!)
                    zip.closeEntry()
                }
            }
            return output.toByteArray()
        } finally {
            directory.deleteRecursively()
        }
    }
}
