package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.startCoroutine

class KotlinEnumConstructorSemanticsTest {
    @Test fun enumOutputIsStableAcrossLazySequentialAndParallelUnits() {
        val sources = listOf(
            DecompiledClass("enumunits.First", "package enumunits; public enum First { A(7L); public final long value; First(long value) { this.value=value; } }"),
            DecompiledClass("enumunits.Second", "package enumunits; public enum Second { B(11L); public final long value; Second(long value) { this.value=value; } }"),
            DecompiledClass("enumunits.Other", "package enumunits; public class Other { public static int healthy() { return 13; } }")
        )
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(sources, sources.first().fullName))
        fun engine() = com.jadxmp.api.Decompiler(com.jadxmp.api.DecompilerArgs(
            outputFormat = com.jadxmp.api.OutputFormat.KOTLIN, parallelism = 4)).also { it.load("enum-units.dex", dex) }
        fun execute(classes: List<DecompiledClass>, kotlin: Boolean) {
            withCompiledClasses(classes, kotlin) { loader ->
                for ((name, expected) in listOf("First" to 7L, "Second" to 11L)) {
                    val cls = loader.loadClass("enumunits.$name")
                    val entry = cls.enumConstants.single()
                    assertEquals(expected, cls.getDeclaredField("value").apply { isAccessible = true }.getLong(entry))
                    assertEquals(0, (entry as Enum<*>).ordinal)
                }
                val other = loader.loadClass("enumunits.Other")
                val target = if (kotlin) other.getField("Companion").get(null) else null
                assertEquals(13, (target?.javaClass ?: other).getMethod("healthy").invoke(target))
            }
        }
        execute(sources, false)
        execute(ReferenceDecompiler().decompile("enum-units.dex", dex).classes, false)
        var expected: Map<String, String>? = null
        for (reverse in listOf(false, true)) {
            val engine = engine()
            val names = if (reverse) engine.classNames.reversed() else engine.classNames
            val classes = names.map { name -> engine.decompileClass(name)!!.also {
                assertEquals(0, it.metadata.errorCount, it.code)
            } }.map { DecompiledClass(it.fullName, it.code) }
            val sourceMap = classes.associate { it.fullName to it.source }
            if (expected == null) expected = sourceMap else assertEquals(expected, sourceMap)
            execute(classes, true)
        }
        repeat(3) {
            val engine = engine()
            val finished = java.util.concurrent.CountDownLatch(1)
            var completed: Result<com.jadxmp.api.DecompilationResult>? = null
            suspend { engine.decompileAllParallel() }.startCoroutine(object : kotlin.coroutines.Continuation<com.jadxmp.api.DecompilationResult> {
                override val context = kotlin.coroutines.EmptyCoroutineContext
                override fun resumeWith(result: Result<com.jadxmp.api.DecompilationResult>) {
                    completed = result
                    finished.countDown()
                }
            })
            assertTrue(finished.await(30, java.util.concurrent.TimeUnit.SECONDS), "parallel decompilation timed out")
            val result = completed!!.getOrThrow()
            assertEquals(0, result.errorCount)
            val classes = result.classes.map { DecompiledClass(it.fullName, it.code) }
            assertEquals(expected, classes.associate { it.fullName to it.source })
            execute(classes, true)
        }
    }

    @Test fun enumArgumentsPreserveNamesOrdinalsWideValuesNullsAndEffects() {
        checkEnum("WideChoice", """
            FIRST(Trace.wide(1), Trace.reference(2)), SECOND(Trace.wide(3), null);
            public final long number;
            public final String text;
            WideChoice(long number, String text) {
                this.number = number;
                this.text = text;
                Trace.note(7);
            }
        """.trimIndent(), 12737L, listOf(0x1_0000_03e8L, "token2", 0x1_0000_0bb8L, null))
    }

    @Test fun delegatedEnumConstructorsPreserveArgumentAndBodyOrder() {
        checkEnum("DelegatedChoice", """
            FIRST(Trace.reference(1)), SECOND(7L, null);
            public final long number;
            public final String text;
            DelegatedChoice(String text) {
                this(Trace.wide(2), text);
                Trace.note(3);
            }
            DelegatedChoice(long number, String text) {
                this.number = number;
                this.text = text;
                Trace.note(4);
            }
        """.trimIndent(), 12434L, listOf(0x1_0000_07d0L, "token1", 7L, null))
    }

    @Test fun declarationSortingCannotChangeEnumOrdinals() {
        checkEnum("UnsortedChoice", """
            ZED(11L, "z"), ALPHA(22L, null);
            public final long number;
            public final String text;
            UnsortedChoice(long number, String text) { this.number = number; this.text = text; }
        """.trimIndent(), 0L, listOf(11L, "z", 22L, null), listOf("ZED", "ALPHA"))
    }

    @Test fun noArgumentConstructorCanDelegateToUserArgumentConstructor() {
        checkEnum("DefaultChoice", """
            FIRST, SECOND(9L, null);
            public final long number;
            public final String text;
            DefaultChoice() { this(5L, "default"); Trace.note(2); }
            DefaultChoice(long number, String text) { this.number = number; this.text = text; Trace.note(1); }
        """.trimIndent(), 121L, listOf(5L, "default", 9L, null))
    }

    @Test fun throwingEntryArgumentDoesNotRunConstructorOrLaterArguments() {
        val helper = DecompiledClass("enumfixtures.FailureTrace", """
            package enumfixtures;
            public class FailureTrace {
                public static int trace;
                public static final IllegalStateException failure = new IllegalStateException("entry");
                public static long fail() { trace = 1; throw failure; }
                public static String later() { trace = 2; return "later"; }
                public static void body() { trace = 3; }
            }
        """.trimIndent())
        val source = DecompiledClass("enumfixtures.FailingChoice", """
            package enumfixtures;
            public enum FailingChoice {
                FIRST(FailureTrace.fail(), FailureTrace.later()), SECOND(0L, null);
                FailingChoice(long value, String text) { FailureTrace.body(); }
            }
        """.trimIndent())
        JavaCompilation.compile(listOf(helper)).use { library ->
            assertTrue(library.result.success)
            val classpath = listOf(library.output)
            val dex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(source), source.fullName, classpath = classpath))
            fun check(classes: List<DecompiledClass>, kotlin: Boolean) {
                withCompiledClasses(classes, kotlin, classpath) { loader ->
                    val helperClass = loader.loadClass(helper.fullName)
                    val failure = helperClass.getField("failure").get(null)
                    val thrown = org.junit.jupiter.api.Assertions.assertThrows(ExceptionInInitializerError::class.java) {
                        Class.forName(source.fullName, true, loader)
                    }
                    assertTrue(thrown.cause === failure)
                    assertEquals(1, helperClass.getField("trace").getInt(null))
                }
            }
            check(listOf(source), false)
            check(ReferenceDecompiler().decompile("failure-enum.dex", dex).classes, false)
            val candidate = KotlinJadxmpDecompiler().decompileKotlin("failure-enum.dex", dex)
            assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString { it.source })
            check(candidate.classes, true)
        }
    }

    @Test fun ownStaticEnumFactoryRemainsExplicitlyUnsupported() {
        checkEnum("SelfChoice", """
            FIRST(self(1), null), SECOND(self(2), null);
            public final long number;
            public final String text;
            SelfChoice(long number, String text) { this.number = number; this.text = text; }
            private static long self(int value) { return Trace.wide(value); }
        """.trimIndent(), 12L, listOf(0x1_0000_03e8L, null, 0x1_0000_07d0L, null), unsupported = true)
    }

    @Test fun emptyEnumRetainsItsUserConstructorDescriptor() {
        val source = DecompiledClass("enumfixtures.EmptyChoice", """
            package enumfixtures;
            public enum EmptyChoice { ; EmptyChoice(long value) {} }
        """.trimIndent())
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(source), source.fullName))
        fun check(classes: List<DecompiledClass>, kotlin: Boolean) {
            withCompiledClasses(classes, kotlin) { loader ->
                val cls = loader.loadClass(source.fullName)
                assertEquals(0, cls.enumConstants.size)
                assertEquals(listOf(listOf("java.lang.String", "int", "long")),
                    cls.declaredConstructors.map { ctor -> ctor.parameterTypes.map { it.name } })
                assertEquals(0, (cls.getMethod("values").invoke(null) as Array<*>).size)
            }
        }
        check(listOf(source), false)
        check(ReferenceDecompiler().decompile("empty-enum.dex", dex).classes, false)
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("empty-enum.dex", dex)
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString { it.source })
        check(candidate.classes, true)
    }

    @Test fun verifiedHiddenOrdinalDelegationRemainsAnExplicitSourceGap() {
        val source = DecompiledClass("enumfixtures.OrdinalChoice", """
            package enumfixtures;
            public enum OrdinalChoice {
                FIRST, SECOND;
                public final long value;
                OrdinalChoice() { this(-1L); }
                OrdinalChoice(long value) { this.value = value; }
            }
        """.trimIndent())
        patchedEnum(source, patch = "ordinal") { loader, dex ->
            val original = loader.loadClass(source.fullName)
            assertEquals(listOf(0L, 1L), original.enumConstants.map { original.getField("value").getLong(it) })
            assertEquals(listOf("FIRST", "SECOND"), original.enumConstants.map { (it as Enum<*>).name })
            val reference = ReferenceDecompiler().decompile("ordinal-enum.dex", dex)
            JavaCompilation.compile(reference.classes).use { compiled ->
                // The pinned source compiler result is measured separately from verified JVM execution.
                org.junit.jupiter.api.Assertions.assertFalse(compiled.result.success, "pinned oracle cannot source the hidden ordinal dependency")
            }
            val candidate = KotlinJadxmpDecompiler().decompileKotlin("ordinal-enum.dex", dex)
            val output = candidate.classes.joinToString("\n") { it.source }
            org.junit.jupiter.api.Assertions.assertFalse(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), output)
            assertTrue(output.contains("enum constructor/source initialization cannot be reconstructed safely"), output)
        }
    }

    @Test fun synchronizedValuesOfVerifiedEnumCannotBeSilentlyRegenerated() {
        val source = DecompiledClass("enumfixtures.LockedChoice", """
            package enumfixtures;
            public enum LockedChoice { FIRST(7L); LockedChoice(long value) {} }
        """.trimIndent())
        patchedEnum(source, patch = "sync") { loader, dex ->
            val cls = Class.forName(source.fullName, true, loader)
            val values = cls.getMethod("values")
            assertTrue(java.lang.reflect.Modifier.isSynchronized(values.modifiers))
            val started = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val completed = java.util.concurrent.CountDownLatch(1)
            val worker = Thread {
                started.countDown()
                try { assertEquals(1, (values.invoke(null) as Array<*>).size) }
                catch (problem: Throwable) { failure.set(problem) }
                finally { completed.countDown() }
            }
            synchronized(cls) {
                worker.start()
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, worker.state)
                assertEquals(1L, completed.count)
            }
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            val reference = ReferenceDecompiler().decompile("locked-enum.dex", dex)
            withCompiledClasses(reference.classes, false) { referenceLoader ->
                // The original bytecode blocks on the Class monitor; the pinned reconstruction loses it.
                org.junit.jupiter.api.Assertions.assertFalse(java.lang.reflect.Modifier.isSynchronized(
                    referenceLoader.loadClass(source.fullName).getMethod("values").modifiers))
            }
            val candidate = KotlinJadxmpDecompiler().decompileKotlin("locked-enum.dex", dex)
            val output = candidate.classes.joinToString("\n") { it.source }
            org.junit.jupiter.api.Assertions.assertFalse(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), output)
            assertTrue(output.contains("enum constructor/source initialization cannot be reconstructed safely"), output)
        }
    }

    @Test fun backingArrayAndEntryMutationOfVerifiedEnumsRemainExplicitSourceGaps() {
        val source = DecompiledClass("enumfixtures.RawChoice", """
            package enumfixtures;
            public enum RawChoice { FIRST(1L), SECOND(2L); RawChoice(long value) {} }
        """.trimIndent())
        for (patch in listOf("backing", "rewrite")) patchedEnum(source, patch) { loader, dex ->
            val cls = Class.forName(source.fullName, true, loader)
            val values = cls.getMethod("values")
            if (patch == "backing") {
                val backing = cls.getMethod("backing").invoke(null) as Array<*>
                assertTrue(backing === cls.getDeclaredField("\$VALUES").apply { isAccessible = true }.get(null))
                assertTrue(backing !== values.invoke(null))
                java.lang.reflect.Array.set(backing, 0, null)
                assertEquals(null, (values.invoke(null) as Array<*>)[0])
            } else {
                assertTrue(cls.getField("FIRST").get(null) === cls.getField("SECOND").get(null))
                assertTrue((values.invoke(null) as Array<*>)[0] !== cls.getField("FIRST").get(null))
            }
            val candidate = KotlinJadxmpDecompiler().decompileKotlin("$patch-enum.dex", dex)
            val output = candidate.classes.joinToString("\n") { it.source }
            org.junit.jupiter.api.Assertions.assertFalse(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), output)
            assertTrue(output.contains("enum constructor/source initialization cannot be reconstructed safely"), output)
        }
    }

    @Test fun previousEntriesWorkButForwardAndConstructorEntryReadsNeedDiagnostics() {
        val source = DecompiledClass("enumfixtures.LinkedChoice", """
            package enumfixtures;
            public enum LinkedChoice {
                FIRST(null), SECOND(FIRST);
                public final LinkedChoice previous;
                LinkedChoice(LinkedChoice previous) { this.previous = previous; }
            }
        """.trimIndent())
        fun check(loader: ClassLoader) {
            val cls = Class.forName(source.fullName, true, loader)
            val entries = cls.enumConstants
            val previous = cls.getDeclaredField("previous").apply { isAccessible = true }
            assertEquals(null, previous.get(entries[0]))
            assertTrue(previous.get(entries[1]) === entries[0])
        }
        for (patch in listOf("none", "forward", "constructor")) patchedEnum(source, patch) { loader, dex ->
            check(loader)
            val candidate = KotlinJadxmpDecompiler().decompileKotlin("$patch-enum.dex", dex)
            val output = candidate.classes.joinToString("\n") { it.source }
            if (patch == "none") {
                withCompiledClasses(ReferenceDecompiler().decompile("linked-enum.dex", dex).classes, false, action = ::check)
                assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), output)
                withCompiledClasses(candidate.classes, true, action = ::check)
            } else {
                org.junit.jupiter.api.Assertions.assertFalse(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), output)
                assertTrue(output.contains("enum constructor/source initialization cannot be reconstructed safely"), output)
            }
        }
    }

    @Test fun enumBuiltinEntryNamesNeedDiagnosticsWithoutRenamingIdentity() {
        checkEnum("BuiltinEntries", """
            name(1L, null), ordinal(2L, null);
            public final long number;
            public final String text;
            BuiltinEntries(long number, String text) { this.number = number; this.text = text; }
        """.trimIndent(), 0L, listOf(1L, null, 2L, null), listOf("name", "ordinal"), unsupported = true)
        checkEnum("BuiltinEntries2", """
            entries(1L, null), SECOND(2L, null);
            public final long number;
            public final String text;
            BuiltinEntries2(long number, String text) { this.number = number; this.text = text; }
        """.trimIndent(), 0L, listOf(1L, null, 2L, null), listOf("entries", "SECOND"), unsupported = true)
        checkEnum("AllowedBuiltinSpellings", """
            values(1L, null), valueOf(2L, null);
            public final long number;
            public final String text;
            AllowedBuiltinSpellings(long number, String text) { this.number = number; this.text = text; }
        """.trimIndent(), 0L, listOf(1L, null, 2L, null), listOf("values", "valueOf"))
    }

    private fun patchedEnum(source: DecompiledClass, patch: String, action: (ClassLoader, ByteArray) -> Unit) {
        JavaCompilation.compile(listOf(source), release = 11).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val file = original.output.resolve(source.fullName.replace('.', '/') + ".class")
            val writer = org.jetbrains.org.objectweb.asm.ClassWriter(org.jetbrains.org.objectweb.asm.ClassWriter.COMPUTE_FRAMES or org.jetbrains.org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
            org.jetbrains.org.objectweb.asm.ClassReader(file.readBytes()).accept(object : org.jetbrains.org.objectweb.asm.ClassVisitor(org.jetbrains.org.objectweb.asm.Opcodes.ASM9, writer) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): org.jetbrains.org.objectweb.asm.MethodVisitor? {
                    if (patch == "ordinal" && name == "<init>" && descriptor == "(Ljava/lang/String;I)V") {
                        val method = super.visitMethod(access, name, descriptor, signature, exceptions)
                        method.visitCode()
                        method.visitVarInsn(org.jetbrains.org.objectweb.asm.Opcodes.ALOAD, 0)
                        method.visitVarInsn(org.jetbrains.org.objectweb.asm.Opcodes.ALOAD, 1)
                        method.visitVarInsn(org.jetbrains.org.objectweb.asm.Opcodes.ILOAD, 2)
                        method.visitVarInsn(org.jetbrains.org.objectweb.asm.Opcodes.ILOAD, 2)
                        method.visitInsn(org.jetbrains.org.objectweb.asm.Opcodes.I2L)
                        method.visitMethodInsn(org.jetbrains.org.objectweb.asm.Opcodes.INVOKESPECIAL, source.fullName.replace('.', '/'), "<init>", "(Ljava/lang/String;IJ)V", false)
                        method.visitInsn(org.jetbrains.org.objectweb.asm.Opcodes.RETURN)
                        method.visitMaxs(0, 0)
                        method.visitEnd()
                        return null
                    }
                    val flags = if (patch == "sync" && name == "values") access or org.jetbrains.org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED else access
                    val delegate = super.visitMethod(flags, name, descriptor, signature, exceptions)
                    return object : org.jetbrains.org.objectweb.asm.MethodVisitor(org.jetbrains.org.objectweb.asm.Opcodes.ASM9, delegate) {
                        private var replaced = false
                        override fun visitInsn(opcode: Int) {
                            if (patch == "forward" && name == "<clinit>" && opcode == org.jetbrains.org.objectweb.asm.Opcodes.ACONST_NULL && !replaced) {
                                replaced = true
                                super.visitFieldInsn(org.jetbrains.org.objectweb.asm.Opcodes.GETSTATIC, source.fullName.replace('.', '/'), "SECOND", "L" + source.fullName.replace('.', '/') + ";")
                            } else {
                                if (patch == "rewrite" && name == "<clinit>" && opcode == org.jetbrains.org.objectweb.asm.Opcodes.RETURN) {
                                    val owner = source.fullName.replace('.', '/')
                                    super.visitFieldInsn(org.jetbrains.org.objectweb.asm.Opcodes.GETSTATIC, owner, "SECOND", "L$owner;")
                                    super.visitFieldInsn(org.jetbrains.org.objectweb.asm.Opcodes.PUTSTATIC, owner, "FIRST", "L$owner;")
                                }
                                super.visitInsn(opcode)
                            }
                        }
                        override fun visitVarInsn(opcode: Int, variable: Int) {
                            if (patch == "constructor" && name == "<init>" && opcode == org.jetbrains.org.objectweb.asm.Opcodes.ALOAD && variable == 3) {
                                val owner = source.fullName.replace('.', '/')
                                super.visitFieldInsn(org.jetbrains.org.objectweb.asm.Opcodes.GETSTATIC, owner, "FIRST", "L$owner;")
                            } else super.visitVarInsn(opcode, variable)
                        }
                    }
                }
                override fun visitEnd() {
                    if (patch == "backing") {
                        val owner = source.fullName.replace('.', '/')
                        val method = super.visitMethod(org.jetbrains.org.objectweb.asm.Opcodes.ACC_PUBLIC or org.jetbrains.org.objectweb.asm.Opcodes.ACC_STATIC,
                            "backing", "()[L$owner;", null, null)
                        method.visitCode()
                        method.visitFieldInsn(org.jetbrains.org.objectweb.asm.Opcodes.GETSTATIC, owner, "\$VALUES", "[L$owner;")
                        method.visitInsn(org.jetbrains.org.objectweb.asm.Opcodes.ARETURN)
                        method.visitMaxs(0, 0)
                        method.visitEnd()
                    }
                    super.visitEnd()
                }
            }, 0)
            file.writeBytes(writer.toByteArray())
            val output = java.nio.file.Files.createTempDirectory("jadxmp-enum-proof").toFile()
            try {
                val command = com.android.tools.r8.D8Command.builder().addProgramFiles(file.toPath())
                    .setMode(com.android.tools.r8.CompilationMode.DEBUG).setMinApiLevel(26)
                    .setOutput(output.toPath(), com.android.tools.r8.OutputMode.DexIndexed)
                AndroidSdk.androidJar()?.let { command.addLibraryFiles(it.toPath()) }
                com.android.tools.r8.D8.run(command.build())
                java.net.URLClassLoader(arrayOf(original.output.toURI().toURL()), javaClass.classLoader).use { loader ->
                    action(loader, output.resolve("classes.dex").readBytes())
                }
            } finally { output.deleteRecursively() }
        }
    }

    private fun checkEnum(name: String, body: String, expectedTrace: Long, expectedFields: List<Any?>, names: List<String> = listOf("FIRST", "SECOND"), unsupported: Boolean = false) {
        val helper = DecompiledClass("enumfixtures.Trace", """
            package enumfixtures;
            public class Trace {
                public static long trace;
                public static String lastReference;
                public static void note(int value) { trace = trace * 10 + value; }
                public static long wide(int value) { note(value); return 0x100000000L + value * 1000L; }
                public static String reference(int value) { note(value); lastReference = "token" + value; return lastReference; }
            }
        """.trimIndent())
        val source = DecompiledClass("enumfixtures.$name", "package enumfixtures; public enum $name { $body }")
        JavaCompilation.compile(listOf(helper)).use { library ->
            assertTrue(library.result.success, library.result.diagnostics.toString())
            val classpath = listOf(library.output)
            val dex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(source), source.fullName, classpath = classpath))
            fun measure(classes: List<DecompiledClass>, kotlin: Boolean): List<Any?> {
                val observed = mutableListOf<Any?>()
                withCompiledClasses(classes, kotlin, classpath) { loader ->
                    val cls = Class.forName(source.fullName, true, loader)
                    val entries = cls.enumConstants
                    assertEquals(2, entries.size)
                    val signatures = cls.declaredConstructors.map { ctor ->
                        assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.modifiers))
                        ctor.parameterTypes.map { it.name }
                    }.toSet()
                    val expectedSignatures = mutableSetOf(listOf("java.lang.String", "int", "long", "java.lang.String"))
                    if (name == "DefaultChoice") expectedSignatures.add(listOf("java.lang.String", "int"))
                    if (name == "DelegatedChoice") expectedSignatures.add(listOf("java.lang.String", "int", "java.lang.String"))
                    assertEquals(expectedSignatures, signatures)
                    for ((index, value) in entries.withIndex()) {
                        observed += (value as Enum<*>).name
                        observed += value.ordinal
                        assertEquals(index, value.ordinal)
                        for (field in listOf("number", "text")) {
                            observed += cls.getDeclaredField(field).apply { isAccessible = true }.get(value)
                        }
                    }
                    val originalReference = loader.loadClass(helper.fullName).getField("lastReference").get(null)
                    if (originalReference != null) assertTrue(cls.getDeclaredField("text").apply { isAccessible = true }.get(entries[0]) === originalReference)
                    assertEquals(java.lang.Long.TYPE, cls.getDeclaredField("number").type)
                    assertEquals(String::class.java, cls.getDeclaredField("text").type)
                    val values = cls.getMethod("values")
                    val first = values.invoke(null) as Array<*>
                    val second = values.invoke(null) as Array<*>
                    observed += (first !== second)
                    observed += (first[0] === entries[0] && first[1] === entries[1])
                    observed += (cls.getMethod("valueOf", String::class.java).invoke(null, names[1]) === entries[1])
                    observed += loader.loadClass(helper.fullName).getField("trace").getLong(null)
                }
                return observed
            }
            val expected = listOf(names[0], 0, expectedFields[0], expectedFields[1],
                names[1], 1, expectedFields[2], expectedFields[3], true, true, true, expectedTrace)
            assertEquals(expected, measure(listOf(source), false), "original JVM")
            val reference = ReferenceDecompiler().decompile("$name.dex", dex)
            assertEquals(expected, measure(reference.classes, false), "pinned original reference")
            val candidate = KotlinJadxmpDecompiler().decompileKotlin("$name.dex", dex)
            val output = candidate.classes.joinToString("\n") { it.source }
            if (unsupported) {
                // This is a representational gap, not parity: K2 rejects enum companion access while
                // entries are initializing. Original and pinned JVM execution above remain strict.
                org.junit.jupiter.api.Assertions.assertFalse(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), output)
                assertTrue(output.contains("enum constructor/source initialization cannot be reconstructed safely"), output)
            } else {
                assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), output)
                assertEquals(expected, measure(candidate.classes, true), output)
            }
        }
    }
}
