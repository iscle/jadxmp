package com.jadxmp.pipeline.throwsinfer

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.input.CodeReader
import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.types.ClassHierarchy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThrowsInferenceTest {

    private val CLS = "Lcom/example/C;"
    private lateinit var root: IrRoot
    private lateinit var cls: IrClass

    private fun ctor(type: String) = FakeMethodRef(type, "<init>", "V", emptyList())

    private fun setup() {
        root = IrRoot()
        cls = IrClass(root, "com.example.C", 0, superType = IrType.OBJECT)
        root.addClass(cls)
    }

    private fun method(name: String, reader: CodeReader): IrMethod {
        val m = IrMethod(cls, name, IrType.VOID, emptyList(), IrMethod.ACC_STATIC)
        m[PipelineAttrs.CODE_READER] = reader
        m[PipelineAttrs.REGISTER_COUNT] = reader.registerCount
        cls.methods.add(m)
        return m
    }

    private fun throwOf(type: String, tries: List<FakeTryBlock> = emptyList(), extra: List<Insn> = emptyList()) =
        FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = type),
                Insn(Opcode.INVOKE_DIRECT, 1, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctor(type)),
                Insn(Opcode.THROW, 2, intArrayOf(0)),
            ) + extra,
            tries = tries,
        )

    private fun infer(m: IrMethod) = ThrowsInference(root, ClassHierarchy(root)).apply(m)

    private fun throwsOf(m: IrMethod): List<String> =
        (m[CodegenKeys.THROWS] ?: emptyList()).map { (it as IrType.Object).className }

    @Test
    fun checkedThrowIsDeclared() {
        setup()
        val m = method("thrower", throwOf("Ljava/io/FileNotFoundException;"))
        infer(m)
        assertEquals(listOf("java.io.FileNotFoundException"), throwsOf(m))
    }

    @Test
    fun uncheckedThrowIsNotDeclared() {
        setup()
        val m = method("boom", throwOf("Ljava/lang/IllegalStateException;"))
        infer(m)
        assertNull(m[CodegenKeys.THROWS], "unchecked (RuntimeException family) must not be declared")
    }

    @Test
    fun throwsPropagateThroughCalls() {
        setup()
        method("thrower", throwOf("Ljava/io/FileNotFoundException;"))
        val caller = method(
            "caller",
            FakeCodeReader(
                1,
                listOf(
                    Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = FakeMethodRef(CLS, "thrower", "V", emptyList())),
                    Insn(Opcode.RETURN_VOID, 1),
                ),
            ),
        )
        infer(caller)
        assertEquals(listOf("java.io.FileNotFoundException"), throwsOf(caller))
    }

    @Test
    fun mutuallyRecursiveCalleesConverge() {
        setup()
        method("thrower", throwOf("Ljava/io/FileNotFoundException;"))
        // a() -> b(); b() -> thrower() + a()
        method(
            "a",
            FakeCodeReader(1, listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = FakeMethodRef(CLS, "b", "V", emptyList())),
                Insn(Opcode.RETURN_VOID, 1),
            )),
        )
        val b = method(
            "b",
            FakeCodeReader(1, listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = FakeMethodRef(CLS, "thrower", "V", emptyList())),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = FakeMethodRef(CLS, "a", "V", emptyList())),
                Insn(Opcode.RETURN_VOID, 2),
            )),
        )
        infer(b)
        assertEquals(listOf("java.io.FileNotFoundException"), throwsOf(b))
    }

    @Test
    fun caughtExceptionIsNotDeclared() {
        setup()
        // throw FNFE inside a try that catches Exception -> not propagated.
        val reader = throwOf(
            "Ljava/io/FileNotFoundException;",
            tries = listOf(FakeTryBlock(0, 2, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(3), -1))),
            extra = listOf(
                Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(1)),
                Insn(Opcode.RETURN_VOID, 4),
            ),
        )
        val m = method("guarded", reader)
        infer(m)
        assertNull(m[CodegenKeys.THROWS], "an exception caught by an enclosing catch must not be declared")
    }

    @Test
    fun unresolvableLibraryCalleeIsOmittedNotGuessed() {
        setup()
        // Calls a library method we can't resolve: no throws inferred (under-declare, sound).
        val m = method(
            "callsLibrary",
            FakeCodeReader(1, listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = FakeMethodRef("Ljava/lang/System;", "gc", "V", emptyList())),
                Insn(Opcode.RETURN_VOID, 1),
            )),
        )
        infer(m)
        assertNull(m[CodegenKeys.THROWS])
    }
}
