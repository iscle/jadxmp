package com.jadxmp.oracle

import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KotlinPublicFieldAbiSemanticsTest {
    @Test fun unchangedJavaFieldClientsPreserveDescriptorsFlagsDefaultsAndIdentity() {
        val types = listOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "Object", "String", "int[]", "Integer", "Float")
        val values = listOf("true", "(byte)-7", "(char)65535", "(short)-123", "Integer.MIN_VALUE", "Long.MIN_VALUE", "-0.0f", "-0.0d", "token", "text", "array", "integerRef", "floatRef")
        val target = DecompiledClass("fieldabi.Fields", buildString {
            append("package fieldabi; public class Fields {\n")
            for ((index, type) in types.withIndex()) append("public $type f$index; public static $type s$index;\n")
            append("public volatile long observed; public static volatile int observedStatic; public transient Object payload;\n")
            append("public static int initialized=6; static { initialized++; }\n}")
        })
        val client = DecompiledClass("fieldabi.BinaryClient", buildString {
            append("package fieldabi; public class BinaryClient { public static int check(Fields value) {\n")
            append("if(Fields.initialized!=7)throw new AssertionError(\"initialization\");\n")
            for (index in types.indices) {
                when (index) {
                    0 -> append("if(value.f0||Fields.s0)throw new AssertionError(\"default boolean\");\n")
                    6 -> append("if(Float.floatToRawIntBits(value.f6)!=0||Float.floatToRawIntBits(Fields.s6)!=0)throw new AssertionError(\"default float bits\");\n")
                    7 -> append("if(Double.doubleToRawLongBits(value.f7)!=0L||Double.doubleToRawLongBits(Fields.s7)!=0L)throw new AssertionError(\"default double bits\");\n")
                    in 1..5 -> append("if(value.f$index!=0||Fields.s$index!=0)throw new AssertionError(\"default f$index\");\n")
                    else -> append("if(value.f$index!=null||Fields.s$index!=null)throw new AssertionError(\"default f$index\");\n")
                }
            }
            append("if(value.observed!=0L||Fields.observedStatic!=0||value.payload!=null)throw new AssertionError(\"modifier defaults\");\n")
            append("Object token=new Object(); String text=new String(\"text\"); int[] array={7}; Integer integerRef=new Integer(31); Float floatRef=new Float(1.25f);\n")
            for (index in types.indices) {
                append("value.f$index=${values[index]};Fields.s$index=${values[index]};\n")
                if (index == 6) append("if(Float.floatToRawIntBits(value.f6)!=0x80000000||Float.floatToRawIntBits(Fields.s6)!=0x80000000)throw new AssertionError();\n")
                else if (index == 7) append("if(Double.doubleToRawLongBits(value.f7)!=0x8000000000000000L||Double.doubleToRawLongBits(Fields.s7)!=0x8000000000000000L)throw new AssertionError();\n")
                else append("if(value.f$index!=${values[index]}||Fields.s$index!=${values[index]})throw new AssertionError(\"f$index\");\n")
            }
            append("value.payload=token;value.observed=123;Fields.observedStatic=456;\n")
            append("value.f8=null;Fields.s9=null;value.f10=null;if(value.f8!=null||Fields.s9!=null||value.f10!=null)throw new AssertionError();\n")
            append("return 17;} }")
        })
        verify(target, client) { cls ->
            for ((index, type) in types.withIndex()) {
                val instance = cls.getDeclaredField("f$index")
                val static = cls.getDeclaredField("s$index")
                assertEquals(Modifier.PUBLIC, instance.modifiers)
                assertEquals(Modifier.PUBLIC or Modifier.STATIC, static.modifiers)
                assertEquals(when (type) { "Object", "String", "Integer", "Float" -> "java.lang.$type"; "int[]" -> "[I"; else -> type }, instance.type.name)
                assertEquals(instance.type, static.type)
            }
            assertEquals(Modifier.PUBLIC or Modifier.VOLATILE, cls.getDeclaredField("observed").modifiers)
            assertEquals(Modifier.PUBLIC or Modifier.STATIC or Modifier.VOLATILE, cls.getDeclaredField("observedStatic").modifiers)
            assertEquals(Modifier.PUBLIC or Modifier.TRANSIENT, cls.getDeclaredField("payload").modifiers)
            assertFalse(cls.declaredMethods.any { it.name.startsWith("getF") || it.name.startsWith("setF") || it.name == "getObserved" || it.name == "setObserved" })
        }
    }

    @Test fun realGetterAndSetterMethodsRemainDistinctFromRawFields() {
        val target = DecompiledClass("fieldabi.Accessors", """
            package fieldabi;
            public class Accessors {
                public int count;
                public boolean isReady;
                public int getCount(){return count+100;}
                public void setCount(int value){count=value+1;}
                public boolean isReady(){return !isReady;}
            }
        """.trimIndent())
        val client = DecompiledClass("fieldabi.AccessorClient", """
            package fieldabi;
            public class AccessorClient {
                public static int check(Accessors value){
                    value.count=3;
                    if(value.getCount()!=103)throw new AssertionError();
                    value.setCount(8);
                    if(value.count!=9)throw new AssertionError();
                    value.isReady=true;
                    if(value.isReady())throw new AssertionError();
                    return 17;
                }
            }
        """.trimIndent())
        verify(target, client) { cls ->
            assertEquals(1, cls.declaredMethods.count { it.name == "getCount" })
            assertEquals(1, cls.declaredMethods.count { it.name == "setCount" })
            assertEquals(1, cls.declaredMethods.count { it.name == "isReady" })
            assertFalse(cls.declaredMethods.any { it.name == "setReady" })
        }
    }


    @Test fun inheritedTypeAndFieldNamesCannotCaptureJvmFieldAnnotation() {
        val base = DecompiledClass("fieldalias.Base", """
            package fieldalias;
            public class Base {
                public static class KotlinJvmField {}
                public int KotlinJvmField2;
            }
        """.trimIndent())
        val target = DecompiledClass("fieldalias.Owner", """
            package fieldalias;
            public class Owner extends Base { public int value; public static int total; }
        """.trimIndent())
        val client = DecompiledClass("fieldalias.BinaryClient", """
            package fieldalias;
            public class BinaryClient { public static int check(Owner value) {
                value.value=4;Owner.total=8;return value.value+Owner.total+5;
            } }
        """.trimIndent())
        verify(target, client, listOf(base)) { cls ->
            assertTrue(Modifier.isPublic(cls.getDeclaredField("value").modifiers))
            assertTrue(Modifier.isPublic(cls.getDeclaredField("total").modifiers))
        }
    }

    @Test fun inheritedStaticAndInstanceFieldsRetainTheirDistinctContainers() {
        val base = DecompiledClass("fieldcontainers.Base", """
            package fieldcontainers;
            public class Base { public int first; public static int second; }
        """.trimIndent())
        val target = DecompiledClass("fieldcontainers.Child", """
            package fieldcontainers;
            public class Child extends Base { public static int first; public int second; }
        """.trimIndent())
        val client = DecompiledClass("fieldcontainers.BinaryClient", """
            package fieldcontainers;
            public class BinaryClient { public static int check(Child value) {
                ((Base)value).first=1;Child.first=2;Base.second=3;value.second=11;
                return ((Base)value).first+Child.first+Base.second+value.second;
            } }
        """.trimIndent())
        verify(target, client, listOf(base)) { cls ->
            assertEquals(Modifier.PUBLIC or Modifier.STATIC, cls.getDeclaredField("first").modifiers)
            assertEquals(Modifier.PUBLIC, cls.getDeclaredField("second").modifiers)
            assertEquals(Modifier.PUBLIC, cls.superclass.getDeclaredField("first").modifiers)
            assertEquals(Modifier.PUBLIC or Modifier.STATIC, cls.superclass.getDeclaredField("second").modifiers)
        }
    }

    @Test fun backtickedKeywordRetainsBinaryFieldName() {
        val target = DecompiledClass("fieldkeyword.KotlinJvmField", """
            package fieldkeyword;
            public class KotlinJvmField { public int when; public static int object; }
        """.trimIndent())
        val client = DecompiledClass("fieldkeyword.BinaryClient", """
            package fieldkeyword;
            public class BinaryClient { public static int check(KotlinJvmField value) {
                value.when=4;KotlinJvmField.object=13;return value.when+KotlinJvmField.object;
            } }
        """.trimIndent())
        verify(target, client) { cls ->
            assertEquals(Modifier.PUBLIC, cls.getDeclaredField("when").modifiers)
            assertEquals(Modifier.PUBLIC or Modifier.STATIC, cls.getDeclaredField("object").modifiers)
            assertFalse(cls.declaredMethods.any { it.name == "getWhen" || it.name == "setWhen" })
        }
    }

    @Test fun generatedInstancePropertyHidingRemainsAnExplicitCompilerLimitation() {
        val base = DecompiledClass("fieldhidden.Base", "package fieldhidden; public class Base { public int value; }")
        val target = DecompiledClass("fieldhidden.Child", "package fieldhidden; public class Child extends Base { public int value; }")
        val client = DecompiledClass("fieldhidden.BinaryClient", """
            package fieldhidden;
            public class BinaryClient { public static int check(Child value) {
                ((Base)value).value=3;value.value=14;return ((Base)value).value+value.value;
            } }
        """.trimIndent())
        val originals = listOf(base, target)
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(originals, target.fullName))
        val reference = ReferenceDecompiler().decompile("hidden-fields", dex)
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("hidden-fields", dex)
        JavaCompilation.compile(originals + client).use { original ->
            assertTrue(original.result.success)
            for (source in originals) assertTrue(original.output.resolve(source.fullName.replace('.', '/') + ".class").delete())
            for (classes in listOf(originals, reference.classes)) withCompiledClasses(classes, false, listOf(original.output)) { loader ->
                val cls = loader.loadClass(target.fullName)
                assertEquals(17, loader.loadClass(client.fullName).getMethod("check", cls).invoke(null, cls.getConstructor().newInstance()))
            }
            // Language control: a Java dependency permits the hidden raw field. When both owners
            // are generated Kotlin, property hiding is a separate unsupported source constraint.
            withCompiledClasses(listOf(base), false) { loader ->
                val location = java.io.File(loader.loadClass(base.fullName).protectionDomain.codeSource.location.toURI())
                withCompiledClass(DecompiledClass(target.fullName, "package fieldhidden; class Child:Base() { @JvmField var value:Int=0 }"), true,
                    listOf(location, original.output)) { cls ->
                    assertEquals(17, cls.classLoader.loadClass(client.fullName).getMethod("check", cls).invoke(null, cls.getConstructor().newInstance()))
                }
            }
            val compiled = KotlinAccuracySignals.recompiles(candidate.classes)
            assertEquals(KotlinRecompileStatus.ERRORS, compiled.status)
            assertTrue(compiled.errors.any { it.contains("hides member") || it.contains("override") }, compiled.errors.toString())
        }
    }

    private fun verify(target: DecompiledClass, client: DecompiledClass, dependencies: List<DecompiledClass> = emptyList(), inspect: (Class<*>) -> Unit) {
        val originals = dependencies + target
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(originals, target.fullName))
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("public-fields", dex)
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString { it.source })
        val reference = ReferenceDecompiler().decompile("public-fields", dex)
        assertEquals(0, reference.reportedErrors)
        JavaCompilation.compile(originals + client).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            // Keep the Java client's original bytecode; every run resolves its field/method refs
            // against the rebuilt target, never a recompiled or accidentally retained original.
            for (source in originals) {
                val binary = original.output.resolve(source.fullName.replace('.', '/') + ".class")
                val prefix = binary.name.removeSuffix(".class")
                for (file in binary.parentFile.listFiles()!!.filter { it.name == "$prefix.class" || it.name.startsWith("$prefix\$") }) assertTrue(file.delete())
            }
            for ((classes, kotlin) in listOf(originals to false, reference.classes to false, candidate.classes to true)) {
                withCompiledClasses(classes, kotlin, listOf(original.output)) { loader ->
                    val cls = loader.loadClass(target.fullName)
                    assertEquals(17, loader.loadClass(client.fullName).getMethod("check", cls).invoke(null, cls.getConstructor().newInstance()))
                    inspect(cls)
                }
            }
        }
    }
}
