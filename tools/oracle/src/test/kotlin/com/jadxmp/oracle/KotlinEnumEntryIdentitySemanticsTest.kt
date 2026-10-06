package com.jadxmp.oracle

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.coroutines.startCoroutine

/** Raw smali keeps the constructor SSA values live through the values-array store. */
class KotlinEnumEntryIdentitySemanticsTest {
    @Test fun reusedConstructorResultsPreserveIdentityWideValuesAndEffectOrder() = verify(false)
    @Test fun throwingSecondArgumentNeverRepeatsTheFirstConstruction() = verify(true)

    @Test fun reusedEntryLocalsRemainStableInParallelOutputUnits() = verify(false, parallel = true)

    private fun verify(throwSecond: Boolean, parallel: Boolean = false) {
        JavaCompilation.compile(listOf(helper)).use { library ->
            assertTrue(library.result.success, library.result.diagnostics.toString())
            val directory = Files.createTempDirectory("jadxmp-enum-entry-identity").toFile()
            try {
                val file = directory.resolve("Choice.smali").apply { writeText(smali) }
                val sibling = directory.resolve("Other.smali").apply { writeText("""
                    .class public Lentryidentity/Other;
                    .super Ljava/lang/Object;
                    .method public static healthy()I
                        .locals 1
                        const/4 v0, 7
                        return v0
                    .end method
                """.trimIndent()) }
                val assembled = SmaliAssembler.assemble(listOf(file, sibling))
                assertTrue(assembled.ok, assembled.error)
                val dex = requireNotNull(assembled.dex)
                val reference = ReferenceDecompiler().decompile("entry-identities.dex", dex)
                val candidate = KotlinJadxmpDecompiler().decompileKotlin("entry-identities.dex", dex)
                assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString { it.source })
                val outputs = mutableListOf(listOf(original) to false, reference.classes to false, candidate.classes to true)
                if (parallel) {
                    repeat(3) {
                        val engine = com.jadxmp.api.Decompiler(com.jadxmp.api.DecompilerArgs(
                            outputFormat = com.jadxmp.api.OutputFormat.KOTLIN, parallelism = 4))
                        engine.load("entry-identities.dex", dex)
                        val finished = java.util.concurrent.CountDownLatch(1)
                        var completed: Result<com.jadxmp.api.DecompilationResult>? = null
                        suspend { engine.decompileAllParallel() }.startCoroutine(object : kotlin.coroutines.Continuation<com.jadxmp.api.DecompilationResult> {
                            override val context = kotlin.coroutines.EmptyCoroutineContext
                            override fun resumeWith(result: Result<com.jadxmp.api.DecompilationResult>) {
                                completed = result; finished.countDown()
                            }
                        })
                        assertTrue(finished.await(30, java.util.concurrent.TimeUnit.SECONDS))
                        val result = completed!!.getOrThrow()
                        assertEquals(0, result.errorCount)
                        val classes = result.classes.map { DecompiledClass(it.fullName, it.code) }
                        assertEquals(candidate.classes.associate { it.fullName to it.source }, classes.associate { it.fullName to it.source })
                        outputs.add(classes to true)
                    }
                }
                for ((classes, kotlin) in outputs) {
                    withCompiledClasses(classes, kotlin, listOf(library.output)) { loader ->
                        val trace = loader.loadClass("entryidentity.Trace")
                        trace.getField("failSecond").setBoolean(null, throwSecond)
                        if (throwSecond) {
                            val thrown = assertThrows(ExceptionInInitializerError::class.java) {
                                Class.forName("entryidentity.Choice", true, loader)
                            }
                            assertSame(trace.getField("FAILURE").get(null), thrown.cause)
                            assertEquals(132, trace.getField("state").getInt(null))
                        } else {
                            val cls = Class.forName("entryidentity.Choice", true, loader)
                            val values = cls.getMethod("values")
                            val entries = values.invoke(null) as Array<*>
                            val secondRead = values.invoke(null) as Array<*>
                            assertNotSame(entries, secondRead)
                            assertEquals(listOf("FIRST", "SECOND"), entries.map { (it as Enum<*>).name })
                            val number = cls.getDeclaredField("number").apply { isAccessible = true }
                            val text = cls.getDeclaredField("text").apply { isAccessible = true }
                            assertEquals(listOf(0x1_0000_0003L, 0x2_0000_0006L), entries.map(number::getLong))
                            assertEquals("payload", text.get(entries[0]))
                            assertNull(text.get(entries[1]))
                            for (index in entries.indices) {
                                assertSame(entries[index], secondRead[index])
                                assertSame(entries[index], cls.getField(if (index == 0) "FIRST" else "SECOND").get(null))
                                assertSame(entries[index], cls.getMethod("valueOf", String::class.java).invoke(null, (entries[index] as Enum<*>).name))
                                assertEquals(index, (entries[index] as Enum<*>).ordinal)
                            }
                            assertEquals(1323, trace.getField("state").getInt(null))
                            cls.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType,
                                Long::class.javaPrimitiveType, String::class.java)
                        }
                    }
                }
            } finally { directory.deleteRecursively() }
        }
    }

    private val helper = DecompiledClass("entryidentity.Trace", """
        package entryidentity;
        public class Trace {
            public static int state;
            public static boolean failSecond;
            public static final RuntimeException FAILURE = new IllegalStateException("second");
            public static void note(int n) { state = state * 10 + n; }
            public static long argument(int n) {
                note(n);
                if (n == 2 && failSecond) throw FAILURE;
                return n * 0x100000003L;
            }
        }
    """.trimIndent())
    private val original = DecompiledClass("entryidentity.Choice", """
        package entryidentity;
        public enum Choice {
            FIRST(Trace.argument(1), "payload"), SECOND(Trace.argument(2), null);
            public final long number;
            public final String text;
            Choice(long number, String text) {
                Trace.note(3); this.number = number; this.text = text;
            }
        }
    """.trimIndent())
    private val smali = """
        .class public final enum Lentryidentity/Choice;
        .super Ljava/lang/Enum;
        .field public static final enum FIRST:Lentryidentity/Choice;
        .field public static final enum SECOND:Lentryidentity/Choice;
        .field private static final synthetic ${'$'}VALUES:[Lentryidentity/Choice;
        .field public final number:J
        .field public final text:Ljava/lang/String;
        .method private constructor <init>(Ljava/lang/String;IJLjava/lang/String;)V
            .locals 1
            invoke-direct {p0, p1, p2}, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
            const/4 v0, 3
            invoke-static {v0}, Lentryidentity/Trace;->note(I)V
            iput-wide p3, p0, Lentryidentity/Choice;->number:J
            iput-object p5, p0, Lentryidentity/Choice;->text:Ljava/lang/String;
            return-void
        .end method
        .method static constructor <clinit>()V
            .locals 12
            new-instance v0, Lentryidentity/Choice;
            const-string v1, "FIRST"
            const/4 v2, 0
            const/4 v3, 1
            invoke-static {v3}, Lentryidentity/Trace;->argument(I)J
            move-result-wide v3
            const-string v5, "payload"
            invoke-direct/range {v0 .. v5}, Lentryidentity/Choice;-><init>(Ljava/lang/String;IJLjava/lang/String;)V
            sput-object v0, Lentryidentity/Choice;->FIRST:Lentryidentity/Choice;
            new-instance v6, Lentryidentity/Choice;
            const-string v7, "SECOND"
            const/4 v8, 1
            const/4 v9, 2
            invoke-static {v9}, Lentryidentity/Trace;->argument(I)J
            move-result-wide v9
            const/4 v11, 0
            invoke-direct/range {v6 .. v11}, Lentryidentity/Choice;-><init>(Ljava/lang/String;IJLjava/lang/String;)V
            sput-object v6, Lentryidentity/Choice;->SECOND:Lentryidentity/Choice;
            filled-new-array {v0, v6}, [Lentryidentity/Choice;
            move-result-object v0
            sput-object v0, Lentryidentity/Choice;->${'$'}VALUES:[Lentryidentity/Choice;
            return-void
        .end method
        .method public static values()[Lentryidentity/Choice;
            .locals 1
            sget-object v0, Lentryidentity/Choice;->${'$'}VALUES:[Lentryidentity/Choice;
            invoke-virtual {v0}, [Lentryidentity/Choice;->clone()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, [Lentryidentity/Choice;
            return-object v0
        .end method
        .method public static valueOf(Ljava/lang/String;)Lentryidentity/Choice;
            .locals 1
            const-class v0, Lentryidentity/Choice;
            invoke-static {v0, p0}, Ljava/lang/Enum;->valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
            move-result-object v0
            check-cast v0, Lentryidentity/Choice;
            return-object v0
        .end method
    """.trimIndent()
}
