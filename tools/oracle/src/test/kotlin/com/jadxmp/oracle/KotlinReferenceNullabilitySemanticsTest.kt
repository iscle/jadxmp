package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import com.jadxmp.api.RenameResult
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.kotlin.KotlinCodeGenerator
import com.jadxmp.codegen.kotlin.KotlinCodegenKeys
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeCustomInstruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

class KotlinReferenceNullabilitySemanticsTest {
    @Test
    fun objectEntryNullsCastsAndDereferencesPreserveJvmEffects(@TempDir dir: File) {
        val helper = DecompiledClass("testing.ObjectEffects", """
            package testing;
            public class ObjectEffects {
                public static String trace = "";
                public static boolean failArgument;
                public static boolean failReceiver;
                public Object field;
                public static void prefix() { trace += "P"; }
                public static ObjectEffects receiver(ObjectEffects value) {
                    trace += "R";
                    if (failReceiver) throw new UnsupportedOperationException("receiver");
                    return value;
                }
                public static int index(int value) {
                    trace += "I";
                    if (failArgument) throw new IllegalStateException("index");
                    return value;
                }
                public static char charAt(CharSequence value, int position) { prefix(); return value.charAt(index(position)); }
                public static int callReceiver(ObjectEffects value, Object argumentValue) { prefix(); return receiver(value).consume(argument(argumentValue)); }
                public static void inside() { trace += "M"; }
                public static void lock(Object value) { prefix(); synchronized (value) { inside(); } }
                public static String stringValue(Object value) { prefix(); return String.valueOf(value); }
                public static class NullString { public String toString() { return null; } }
                public static Object argument(Object value) {
                    trace += "A";
                    if (failArgument) throw new IllegalStateException("argument");
                    return value;
                }
                public int consume(Object value) { trace += "C"; return value == null ? 0 : 1; }
                public static Object identity(Object value) { prefix(); return value; }
                public static String branch(Object value) { prefix(); return value == null ? "missing" : "present"; }
                public static int call(ObjectEffects receiver, Object value) { prefix(); return receiver.consume(argument(value)); }
                public static void put(ObjectEffects receiver, Object value) { prefix(); receiver.field = argument(value); }
                public static Object get(ObjectEffects receiver) { prefix(); return receiver.field; }
                public static int length(String value) { prefix(); try { return value.length(); } catch (NullPointerException ex) { return -1; } }
                public static String cast(Object value) { prefix(); return (String) value; }
                public static Object explicit(Object value) { prefix(); return java.util.Objects.requireNonNull(value); }
                public static void fail(Throwable value) throws Throwable { prefix(); throw value; }
                public static Class<?> classType(Object value) { prefix(); return value.getClass(); }
            }
        """.trimIndent())
        val fixture = dir.resolve("ReferenceNulls.smali")
        fixture.writeText("""
            .class public LReferenceNulls;
            .super Ljava/lang/Object;
            .method public static identity(Ljava/lang/Object;)Ljava/lang/Object;
                .locals 0
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                return-object p0
            .end method
            .method public static branch(Ljava/lang/Object;)Ljava/lang/String;
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                if-nez p0, :present
                const-string v0, "missing"
                return-object v0
                :present
                const-string v0, "present"
                return-object v0
            .end method
            .method public static call(Ltesting/ObjectEffects;Ljava/lang/Object;)I
                .locals 1
                .param p0, "receiver"
                .param p1, "argument"
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                invoke-static {p1}, Ltesting/ObjectEffects;->argument(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                invoke-virtual {p0, v0}, Ltesting/ObjectEffects;->consume(Ljava/lang/Object;)I
                move-result v0
                return v0
            .end method
            .method public static callReceiver(Ltesting/ObjectEffects;Ljava/lang/Object;)I
                .locals 2
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                invoke-static {p0}, Ltesting/ObjectEffects;->receiver(Ltesting/ObjectEffects;)Ltesting/ObjectEffects;
                move-result-object v0
                invoke-static {p1}, Ltesting/ObjectEffects;->argument(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                invoke-virtual {v0, v1}, Ltesting/ObjectEffects;->consume(Ljava/lang/Object;)I
                move-result v0
                return v0
            .end method
            .method public static charAt(Ljava/lang/CharSequence;I)C
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                invoke-static {p1}, Ltesting/ObjectEffects;->index(I)I
                move-result v0
                invoke-interface {p0, v0}, Ljava/lang/CharSequence;->charAt(I)C
                move-result v0
                return v0
            .end method
            .method public static lock(Ljava/lang/Object;)V
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                monitor-enter p0
                :lockStart
                invoke-static {}, Ltesting/ObjectEffects;->inside()V
                monitor-exit p0
                :lockEnd
                return-void
                .catchall {:lockStart .. :lockEnd} :lockCatch
                :lockCatch
                move-exception v0
                monitor-exit p0
                throw v0
            .end method
            .method public static stringValue(Ljava/lang/Object;)Ljava/lang/String;
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                invoke-static {p0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            .end method
            .method public static put(Ltesting/ObjectEffects;Ljava/lang/Object;)V
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                invoke-static {p1}, Ltesting/ObjectEffects;->argument(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                iput-object v0, p0, Ltesting/ObjectEffects;->field:Ljava/lang/Object;
                return-void
            .end method
            .method public static get(Ltesting/ObjectEffects;)Ljava/lang/Object;
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                iget-object v0, p0, Ltesting/ObjectEffects;->field:Ljava/lang/Object;
                return-object v0
            .end method
            .method public static length(Ljava/lang/String;)I
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                :start
                invoke-virtual {p0}, Ljava/lang/String;->length()I
                move-result v0
                :end
                .catch Ljava/lang/NullPointerException; {:start .. :end} :caught
                return v0
                :caught
                move-exception v0
                const/4 v0, -0x1
                return v0
            .end method
            .method public static cast(Ljava/lang/Object;)Ljava/lang/String;
                .locals 0
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                check-cast p0, Ljava/lang/String;
                return-object p0
            .end method
            .method public static explicit(Ljava/lang/Object;)Ljava/lang/Object;
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                const-string v0, "value"
                invoke-static {p0, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V
                return-object p0
            .end method
            .method public static fail(Ljava/lang/Throwable;)V
                .locals 0
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                throw p0
            .end method
            .method public static classType(Ljava/lang/Object;)Ljava/lang/Class;
                .locals 1
                invoke-static {}, Ltesting/ObjectEffects;->prefix()V
                invoke-virtual {p0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
                move-result-object v0
                return-object v0
            .end method
        """.trimIndent())
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(decompile(fixture), kotlin = true, additionalClasspath = listOf(dependency.output)) { cls ->
                val target = cls.getField("Companion").get(null)
                val original = cls.classLoader.loadClass(helper.fullName)
                val instance = original.getConstructor().newInstance()
                fun verify(name: String, types: List<Class<*>>, args: List<Any?>, argumentFails: Boolean = false, receiverFails: Boolean = false) {
                    fun observe(owner: Class<*>, receiver: Any?): List<Any?> {
                        original.getField("trace").set(null, "")
                        original.getField("failArgument").setBoolean(null, argumentFails)
                        original.getField("failReceiver").setBoolean(null, receiverFails)
                        original.getField("field").set(instance, null)
                        var failure: Throwable? = null
                        val value = try { owner.getMethod(name, *types.toTypedArray()).invoke(receiver, *args.toTypedArray()) }
                            catch (exception: InvocationTargetException) { failure = exception.cause; null }
                        if (name == "fail" && args[0] != null) assertSame(args[0], failure)
                        if (name == "identity") assertSame(args[0], value)
                        return listOf(value, failure?.javaClass, original.getField("trace").get(null), original.getField("field").get(instance))
                    }
                    assertEquals(observe(original, null), observe(target.javaClass, target), "$name args=$args argumentFails=$argumentFails")
                }
                for (value in listOf(null, "text", Any())) {
                    for (name in listOf("identity", "branch", "cast", "explicit", "classType", "lock")) {
                        verify(name, listOf(Any::class.java), listOf(value))
                    }
                    for (receiver in listOf(null, instance)) for (fails in listOf(false, true)) {
                        verify("call", listOf(original, Any::class.java), listOf(receiver, value), fails)
                        for (receiverFails in listOf(false, true)) verify("callReceiver", listOf(original, Any::class.java), listOf(receiver, value), fails, receiverFails)
                        verify("put", listOf(original, Any::class.java), listOf(receiver, value), fails)
                    }
                }
                for (value in listOf(null, "abc", StringBuilder("abc"))) for (index in listOf(-1, 0, 4)) for (fails in listOf(false, true)) {
                    verify("charAt", listOf(CharSequence::class.java, Int::class.javaPrimitiveType!!), listOf(value, index), fails)
                }
                val nullString = cls.classLoader.loadClass("testing.ObjectEffects\$NullString").getConstructor().newInstance()
                for (value in listOf(null, "text", nullString)) verify("stringValue", listOf(Any::class.java), listOf(value))
                for (receiver in listOf(null, instance)) verify("get", listOf(original), listOf(receiver))
                for (value in listOf(null, "text")) verify("length", listOf(String::class.java), listOf(value))
                for (value in listOf(null, IllegalStateException("identity"))) verify("fail", listOf(Throwable::class.java), listOf(value))
            }
        }
    }

    @Test
    fun dataLikeConstructorAndCopyChecksRemainExecutable(@TempDir dir: File) {
        val helper = DecompiledClass("testing.ConstructorEffects", """
            package testing;
            public class ConstructorEffects {
                public static int calls;
                public static void prefix() { calls++; }
            }
        """.trimIndent())
        val fixture = dir.resolve("CheckedValue.smali")
        fixture.writeText("""
            .class public final LCheckedValue;
            .super Ljava/lang/Object;
            .field public final value:Ljava/lang/String;
            .method public constructor <init>(Ljava/lang/String;)V
                .locals 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                invoke-static {}, Ltesting/ConstructorEffects;->prefix()V
                const-string v0, "value"
                invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V
                iput-object p1, p0, LCheckedValue;->value:Ljava/lang/String;
                return-void
            .end method
            .method public component1()Ljava/lang/String;
                .locals 1
                iget-object v0, p0, LCheckedValue;->value:Ljava/lang/String;
                return-object v0
            .end method
            .method public copy(Ljava/lang/String;)LCheckedValue;
                .locals 1
                invoke-static {}, Ltesting/ConstructorEffects;->prefix()V
                new-instance v0, LCheckedValue;
                invoke-direct {v0, p1}, LCheckedValue;-><init>(Ljava/lang/String;)V
                return-object v0
            .end method
            .method public equals(Ljava/lang/Object;)Z
                .locals 1
                const/4 v0, 0x0
                return v0
            .end method
            .method public hashCode()I
                .locals 1
                const/4 v0, 0x0
                return v0
            .end method
            .method public toString()Ljava/lang/String;
                .locals 1
                const-string v0, "CheckedValue"
                return-object v0
            .end method
        """.trimIndent())
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            val source = decompile(fixture)
            assertTrue(!source.source.contains("data class"), source.source)
            withCompiledClass(source, kotlin = true, additionalClasspath = listOf(dependency.output)) { cls ->
                val effects = cls.classLoader.loadClass(helper.fullName)
                val calls = effects.getField("calls")
                val constructor = cls.getConstructor(String::class.java)
                calls.setInt(null, 0)
                val failure = org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException::class.java) {
                    constructor.newInstance(null as Any?)
                }
                assertTrue(failure.cause is NullPointerException)
                assertEquals(1, calls.getInt(null))
                val value = String(charArrayOf('o', 'k'))
                val instance = constructor.newInstance(value)
                assertSame(value, cls.getMethod("component1").invoke(instance))
                calls.setInt(null, 0)
                val copyFailure = org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException::class.java) {
                    cls.getMethod("copy", String::class.java).invoke(instance, null)
                }
                assertTrue(copyFailure.cause is NullPointerException)
                assertEquals(2, calls.getInt(null))
                calls.setInt(null, 0)
                val copy = cls.getMethod("copy", String::class.java).invoke(instance, value)
                assertSame(value, cls.getMethod("component1").invoke(copy))
                assertEquals(2, calls.getInt(null))
            }
        }
    }

    @Test
    fun provedDataClassKeepsNullableConstructorAndCopyArguments(@TempDir dir: File) {
        val fixture = dir.resolve("NullableValue.smali")
        fixture.writeText("""
            .class public final LNullableValue;
            .super Ljava/lang/Object;
            .field public final value:Ljava/lang/String;
            .method public constructor <init>(Ljava/lang/String;)V
                .locals 0
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                iput-object p1, p0, LNullableValue;->value:Ljava/lang/String;
                return-void
            .end method
            .method public component1()Ljava/lang/String;
                .locals 1
                iget-object v0, p0, LNullableValue;->value:Ljava/lang/String;
                return-object v0
            .end method
            .method public copy(Ljava/lang/String;)LNullableValue;
                .locals 1
                new-instance v0, LNullableValue;
                invoke-direct {v0, p1}, LNullableValue;-><init>(Ljava/lang/String;)V
                return-object v0
            .end method
            .method public equals(Ljava/lang/Object;)Z
                .locals 1
                const/4 v0, 0x0
                return v0
            .end method
            .method public hashCode()I
                .locals 1
                const/4 v0, 0x0
                return v0
            .end method
            .method public toString()Ljava/lang/String;
                .locals 1
                const-string v0, "NullableValue"
                return-object v0
            .end method
        """.trimIndent())
        val source = decompile(fixture)
        assertTrue(source.source.contains("data class"), source.source)
        withCompiledClass(source, kotlin = true) { cls ->
            for (value in listOf(null, String(charArrayOf('o', 'k')))) {
                val instance = cls.getConstructor(String::class.java).newInstance(value)
                assertSame(value, cls.getMethod("component1").invoke(instance))
                for (copyValue in listOf(null, value)) {
                    val copy = cls.getMethod("copy", String::class.java).invoke(instance, copyValue)
                    assertSame(copyValue, cls.getMethod("component1").invoke(copy))
                }
                assertEquals("NullableValue", instance.toString())
            }
        }
        val canonical = fixture.readText()
        for (visibility in listOf("private", "protected")) {
            fixture.writeText(canonical.replace(".method public constructor", ".method $visibility constructor"))
            val restricted = decompile(fixture)
            assertTrue(!restricted.source.contains("data class"), restricted.source)
            withCompiledClass(restricted, kotlin = true) { cls ->
                val constructor = cls.getDeclaredConstructor(String::class.java)
                assertEquals(if (visibility == "private") java.lang.reflect.Modifier.PRIVATE else java.lang.reflect.Modifier.PROTECTED,
                    constructor.modifiers and 7)
                constructor.isAccessible = true
                assertEquals(null, cls.getMethod("component1").invoke(constructor.newInstance(null as Any?)))
            }
        }
        fixture.writeText(canonical.replace(".method public copy", ".method public static copy")
            .replace("invoke-direct {v0, p1}, LNullableValue;", "invoke-direct {v0, p0}, LNullableValue;"))
        val staticCopy = decompile(fixture)
        assertTrue(!staticCopy.source.contains("data class"), staticCopy.source)
        withCompiledClass(staticCopy, kotlin = true) { cls ->
            val target = cls.getField("Companion").get(null)
            val instance = target.javaClass.getMethod("copy", String::class.java).invoke(target, null)
            assertEquals(null, cls.getMethod("component1").invoke(instance))
            assertTrue(cls.methods.none { it.name == "copy" })
        }
        fixture.writeText(canonical)
        val assembly = SmaliAssembler.assemble(fixture)
        assertTrue(assembly.ok, assembly.error)
        for (member in listOf("copy", "component1")) {
            val engine = Decompiler(DecompilerArgs(outputFormat = OutputFormat.KOTLIN))
            engine.load(fixture.name, assembly.dex!!)
            val arguments = if (member == "copy") listOf(IrType.STRING.toString()) else emptyList()
            assertTrue(engine.rename(MethodNodeRef("NullableValue", member, arguments), "renamedMember") is RenameResult.Applied)
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount)
            val renamed = DecompiledClass("NullableValue", result.classes.single().code)
            assertTrue(!renamed.source.contains("data class"), renamed.source)
            withCompiledClass(renamed, kotlin = true) { cls ->
                val instance = cls.getConstructor(String::class.java).newInstance(null as Any?)
                if (member == "copy") {
                    val copy = cls.getMethod("renamedMember", String::class.java).invoke(instance, "value")
                    assertEquals("value", cls.getMethod("component1").invoke(copy))
                } else assertEquals(null, cls.getMethod("renamedMember").invoke(instance))
            }
        }
    }

    @Test
    fun objectOverridesKeepNullIdentityAcrossJavaAndGeneratedKotlinContracts() {
        for (kotlinParent in listOf(false, true)) {
            val root = IrRoot()
            val parentName = if (kotlinParent) "ObjectContract" else "testing.ObjectCallback"
            val parent = IrClass(root, parentName, 0x601)
            root.addClass(parent)
            parent.methods.add(IrMethod(parent, "transform", IrType.OBJECT, listOf(IrType.OBJECT), 0x401))
            val cls = IrClass(root, "ObjectImplementation", 1, interfaces = listOf(IrType.objectType(parentName)))
            root.addClass(cls)
            val method = IrMethod(cls, "transform", IrType.OBJECT, listOf(IrType.OBJECT), 1)
            cls.methods.add(method)
            method[CodegenKeys.PARAM_NAMES] = listOf("value")
            method[KotlinCodegenKeys.IS_OVERRIDE] = true
            val local = LocalVar().apply { name = "value"; type = IrType.OBJECT; add(AttrFlag.METHOD_ARGUMENT) }
            val ssa = SsaValue(1, 0, RegisterOperand(1, IrType.OBJECT)).apply { local.addSsaValue(this); add(AttrFlag.METHOD_ARGUMENT) }
            val parameter = RegisterOperand(1, IrType.OBJECT).also { it.ssaValue = ssa }
            method.blocks.add(BasicBlock(0).apply { instructions.add(Instruction(IrOpcode.RETURN, args = listOf(parameter))) })
            val generated = KotlinCodeGenerator().generate(cls).code
            val source = DecompiledClass(cls.fullName, if (kotlinParent) KotlinCodeGenerator().generate(parent).code + "\n" + generated else generated)
            val helper = DecompiledClass("testing.ObjectCallback", "package testing; public interface ObjectCallback { Object transform(Object value); }")
            JavaCompilation.compile(listOf(helper)).use { dependency ->
                assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
                withCompiledClass(source, kotlin = true, additionalClasspath = listOf(dependency.output)) { implementation ->
                    val instance = implementation.getConstructor().newInstance()
                    val transform = implementation.getMethod("transform", Any::class.java)
                    assertEquals(Any::class.java, transform.returnType)
                    for (value in listOf(null, Any(), "text")) assertSame(value, transform.invoke(instance, value))
                }
            }
        }
    }

    @Test
    fun dynamicReferenceResultsPreserveNullBeforeFollowingEffects() {
        val helper = DecompiledClass("testing.NullBootstrap", """
            package testing;
            import java.lang.invoke.*;
            public class NullBootstrap {
                public static String trace = "";
                public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type) {
                    trace += "B";
                    return new ConstantCallSite(MethodHandles.constant(type.returnType(), null));
                }
                public static void after() { trace += "A"; }
            }
        """.trimIndent())
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            for (type in listOf(IrType.STRING, IrType.array(IrType.STRING))) {
                val root = IrRoot()
                val cls = IrClass(root, "DynamicNull", 1)
                root.addClass(cls)
                val method = IrMethod(cls, "get", type, emptyList(), 9)
                cls.methods.add(method)
                val value = RegisterOperand(0, type)
                val owner = IrType.objectType(helper.fullName)
                val dynamic = InvokeCustomInstruction(
                    MethodRef(owner, "bootstrap", IrType.objectType("java.lang.invoke.CallSite"), listOf(
                        IrType.objectType("java.lang.invoke.MethodHandles.Lookup"), IrType.STRING,
                        IrType.objectType("java.lang.invoke.MethodType"))),
                    InvokeKind.STATIC, "get", type, emptyList(), true, value,
                )
                method.blocks.add(BasicBlock(0).apply { instructions.addAll(listOf(
                    dynamic,
                    InvokeInstruction(MethodRef(owner, "after", IrType.VOID, emptyList()), InvokeKind.STATIC),
                    Instruction(IrOpcode.RETURN, args = listOf(RegisterOperand(0, type))),
                )) })
                val source = DecompiledClass(cls.fullName, KotlinCodeGenerator().generate(cls).code)
                withCompiledClass(source, kotlin = true, additionalClasspath = listOf(dependency.output)) { generated ->
                    val target = generated.getField("Companion").get(null)
                    assertEquals(null, target.javaClass.getMethod("get").invoke(target))
                    assertEquals("BA", generated.classLoader.loadClass(helper.fullName).getField("trace").get(null))
                }
            }
        }
    }

    private fun decompile(smali: File): DecompiledClass {
        val assembly = SmaliAssembler.assemble(smali)
        assertTrue(assembly.ok, assembly.error)
        val result = KotlinJadxmpDecompiler().decompileKotlin(smali.name, assembly.dex!!)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        return result.classes.single()
    }
}
