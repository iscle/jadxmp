package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmFrameTest {
    private val a = JvmFrameValue.Reference("LA;")
    private val b = JvmFrameValue.Reference("LB;")
    private val c = JvmFrameValue.Reference("LC;")
    private val d = JvmFrameValue.Reference("LD;")
    private val long = JvmFrameValue.LongValue
    private val double = JvmFrameValue.DoubleValue

    @Test fun mergesPrimitiveLocalsConservativelyWithoutLeavingOrphanedWideWords() {
        val left = JvmFrame(3, 2)
        left.store(0, long)
        left.store(2, a)
        left.push(JvmFrameValue.IntValue)
        val right = left.snapshot()
        right.store(1, JvmFrameValue.IntValue)
        assertTrue(left.mergeFrom(right))
        assertEquals(JvmLocalSlot.Top, left.slot(0))
        assertEquals(JvmLocalSlot.Top, left.slot(1))
        assertEquals(a, left.local(2))
        assertFalse(left.mergeFrom(right))
        assertEquals(listOf(JvmFrameValue.IntValue), left.stack)
    }

    @Test fun rejectsIncompatibleStackMergesWithoutMutatingExistingState() {
        val left = frame(listOf(JvmFrameValue.IntValue))
        val before = left.stack
        assertFailsWith<ByteReaderException> { left.mergeFrom(frame(listOf(JvmFrameValue.FloatValue))) }
        assertFailsWith<ByteReaderException> { left.mergeFrom(frame(emptyList())) }
        assertEquals(before, left.stack)
    }

    @Test fun supportsAllTwelveDupFormsWithoutLosingValueIdentity() {
        val forms = listOf(
            Form(JvmStackOperation.DUP, listOf(a), listOf(a, a)),
            Form(JvmStackOperation.DUP_X1, listOf(a, b), listOf(b, a, b)),
            Form(JvmStackOperation.DUP_X2, listOf(a, b, c), listOf(c, a, b, c)),
            Form(JvmStackOperation.DUP_X2, listOf(long, a), listOf(a, long, a)),
            Form(JvmStackOperation.DUP2, listOf(a, b), listOf(a, b, a, b)),
            Form(JvmStackOperation.DUP2, listOf(long), listOf(long, long)),
            Form(JvmStackOperation.DUP2_X1, listOf(a, b, c), listOf(b, c, a, b, c)),
            Form(JvmStackOperation.DUP2_X1, listOf(a, long), listOf(long, a, long)),
            Form(JvmStackOperation.DUP2_X2, listOf(a, b, c, d), listOf(c, d, a, b, c, d)),
            Form(JvmStackOperation.DUP2_X2, listOf(a, b, long), listOf(long, a, b, long)),
            Form(JvmStackOperation.DUP2_X2, listOf(long, a, b), listOf(a, b, long, a, b)),
            Form(JvmStackOperation.DUP2_X2, listOf(long, double), listOf(double, long, double)),
        )
        for (form in forms) {
            val frame = frame(listOf(JvmFrameValue.IntValue) + form.before)
            frame.apply(form.operation)
            assertEquals(listOf(JvmFrameValue.IntValue) + form.after, frame.stack, form.operation.name)
            assertEquals(frame.stack.sumOf { it.words }, frame.stackWords)
        }
    }

    @Test fun supportsPopPop2AndCategoryOneSwap() {
        for (form in listOf(
            Form(JvmStackOperation.POP, listOf(a), emptyList()),
            Form(JvmStackOperation.POP2, listOf(a, b), emptyList()),
            Form(JvmStackOperation.POP2, listOf(long), emptyList()),
            Form(JvmStackOperation.SWAP, listOf(a, b), listOf(b, a)),
        )) {
            val frame = frame(listOf(c) + form.before)
            frame.apply(form.operation)
            assertEquals(listOf(c) + form.after, frame.stack)
        }
    }

    @Test fun rejectsEveryIllegalShortCategoryShapeWithoutMutatingFrame() {
        // Bottom-to-top suffixes permitted by the specification, independent of implementation.
        val legalSuffixes = mapOf(
            JvmStackOperation.POP to listOf("1"),
            JvmStackOperation.POP2 to listOf("11", "2"),
            JvmStackOperation.SWAP to listOf("11"),
            JvmStackOperation.DUP to listOf("1"),
            JvmStackOperation.DUP_X1 to listOf("11"),
            JvmStackOperation.DUP_X2 to listOf("111", "21"),
            JvmStackOperation.DUP2 to listOf("11", "2"),
            JvmStackOperation.DUP2_X1 to listOf("111", "12"),
            JvmStackOperation.DUP2_X2 to listOf("1111", "112", "211", "22"),
        )
        for ((operation, suffixes) in legalSuffixes) {
            for (length in 0..4) for (bits in 0 until (1 shl length)) {
                val shape = (0 until length).joinToString("") { if (bits and (1 shl it) == 0) "1" else "2" }
                if (suffixes.any { shape.endsWith(it) }) continue
                val frame = frame(shape.map { if (it == '1') a else long })
                val before = frame.stack
                assertFailsWith<ByteReaderException>("$operation $shape") { frame.apply(operation) }
                assertEquals(before, frame.stack)
                assertEquals(before.sumOf { it.words }, frame.stackWords)
            }
        }
    }

    @Test fun rejectsStackBoundsAndKeepsFailedOperationsAtomic() {
        val frame = JvmFrame(maxLocals = 0, maxStack = 2)
        frame.push(long)
        assertFailsWith<ByteReaderException> { frame.push(a) }
        assertFailsWith<ByteReaderException> { frame.apply(JvmStackOperation.DUP2) }
        assertEquals(listOf(long), frame.stack)
        assertEquals(long, frame.pop())
        assertEquals(0, frame.stackWords)
        assertFailsWith<ByteReaderException> { frame.pop() }
        for ((locals, stack) in listOf(-1 to 0, 0 to -1, 65536 to 0, 0 to 65536)) {
            assertFailsWith<ByteReaderException> { JvmFrame(locals, stack) }
        }
        assertEquals(65535, JvmFrame(65535, 65535).maxLocals)
    }

    @Test fun invalidatesBothWordsOfOverlappingWideLocals() {
        val frame = JvmFrame(maxLocals = 4, maxStack = 4)
        frame.store(0, long)
        frame.store(2, double)
        assertEquals(JvmLocalSlot.Tail, frame.slot(1))
        assertFailsWith<ByteReaderException> { frame.local(1) }
        frame.store(1, a)
        assertEquals(JvmLocalSlot.Top, frame.slot(0))
        assertEquals(a, frame.local(1))
        assertEquals(double, frame.local(2))
        frame.store(1, long)
        assertEquals(long, frame.local(1))
        assertEquals(JvmLocalSlot.Tail, frame.slot(2))
        assertEquals(JvmLocalSlot.Top, frame.slot(3))
        frame.store(1, b)
        assertEquals(JvmLocalSlot.Top, frame.slot(2))
        assertEquals(b, frame.local(1))
    }

    @Test fun validatesLocalBoundsBeforeChangingEitherWord() {
        val frame = JvmFrame(maxLocals = 2, maxStack = 0)
        frame.store(0, long)
        for (index in listOf(-1, 2, Int.MAX_VALUE)) {
            assertFailsWith<ByteReaderException> { frame.local(index) }
            assertFailsWith<ByteReaderException> { frame.store(index, a) }
        }
        assertFailsWith<ByteReaderException> { frame.store(1, double) }
        assertEquals(long, frame.local(0))
        assertEquals(JvmLocalSlot.Tail, frame.slot(1))
    }

    @Test fun snapshotsPreserveOldStackAndLocalsIndependently() {
        val frame = JvmFrame(2, 4)
        frame.store(0, a)
        frame.push(frame.local(0))
        val saved = frame.snapshot()
        frame.store(0, b)
        frame.push(c)
        assertEquals(listOf(a), saved.stack)
        assertEquals(a, saved.local(0))
        saved.store(1, d)
        assertEquals(JvmLocalSlot.Top, frame.slot(1))
        assertEquals(listOf(a, c), frame.stack)
        assertEquals(b, frame.local(0))
    }

    @Test fun constructorSuccessInitializesAllMatchingAliasesOnly() {
        val target = JvmFrameValue.UninitializedNew(3, "LA;")
        val other = JvmFrameValue.UninitializedNew(9, "LA;")
        val frame = JvmFrame(3, 4)
        frame.store(0, target)
        frame.store(1, target)
        frame.store(2, other)
        frame.push(target)
        frame.push(other)
        frame.initialize(target)
        assertEquals(a, frame.local(0))
        assertEquals(a, frame.local(1))
        assertEquals(other, frame.local(2))
        assertEquals(listOf(a, other), frame.stack)
        assertFalse(frame.thisUninitialized)
    }

    @Test fun failedConstructorPoisonsTargetAliasesAndNeverPermitsRetryThroughLocals() {
        val target = JvmFrameValue.UninitializedNew(3, "LA;")
        val other = JvmFrameValue.UninitializedNew(9, "LB;")
        val before = JvmFrame(3, 4)
        before.store(0, target)
        before.store(1, target)
        before.store(2, other)
        before.push(a)
        before.push(target)
        val caught = JvmFrameValue.Reference("Ljava/lang/Exception;")
        val exceptional = before.exceptional(caught, failedConstructor = target)
        assertEquals(listOf(caught), exceptional.stack)
        for (index in 0..1) {
            assertEquals(JvmLocalSlot.Top, exceptional.slot(index))
            assertFailsWith<ByteReaderException> { exceptional.local(index) }
        }
        assertEquals(other, exceptional.local(2))
        assertEquals(target, before.local(0))
        assertEquals(listOf(a, target), before.stack)
    }

    @Test fun ordinaryExceptionsKeepLocalsButDiscardEveryOperandStackValue() {
        val before = JvmFrame(2, 4)
        before.store(0, long)
        before.push(a)
        before.push(double)
        val caught = JvmFrameValue.Reference("Ljava/lang/Throwable;")
        val exceptional = before.exceptional(caught)
        assertEquals(long, exceptional.local(0))
        assertEquals(JvmLocalSlot.Tail, exceptional.slot(1))
        assertEquals(listOf(caught), exceptional.stack)
        assertEquals(1, exceptional.stackWords)
        assertEquals(listOf(a, double), before.stack)
        assertFailsWith<ByteReaderException> { JvmFrame(0, 0).exceptional(caught) }
    }

    @Test fun thisInitializationFlagSurvivesLostAliasesAndFailedConstructor() {
        val target = JvmFrameValue.UninitializedThis("LA;")
        val before = JvmFrame(2, 3, thisUninitialized = true)
        before.store(0, target)
        before.store(1, target)
        before.push(target)
        assertFailsWith<ByteReaderException> { before.requireInitializedThis() }
        val failed = before.exceptional(a, failedConstructor = target)
        assertTrue(failed.thisUninitialized)
        assertFailsWith<ByteReaderException> { failed.requireInitializedThis() }
        assertFailsWith<ByteReaderException> { failed.local(0) }
        before.initialize(target)
        assertFalse(before.thisUninitialized)
        before.requireInitializedThis()
        assertEquals(a, before.local(0))
        assertEquals(listOf(a), before.stack)
        assertTrue(failed.snapshot().thisUninitialized)
        val overwritten = JvmFrame(1, 0, thisUninitialized = true)
        overwritten.store(0, JvmFrameValue.NullValue)
        assertFailsWith<ByteReaderException> { overwritten.requireInitializedThis() }
    }

    @Test fun checksReferenceAndAllocationTokenShapes() {
        assertEquals(1, JvmFrameValue.Reference("[[I").words)
        assertEquals(1, JvmFrameValue.NullValue.words)
        assertEquals(1, JvmFrameValue.FloatValue.words)
        for (descriptor in listOf("I", "V", "L;", "Ljava.lang.String;")) {
            assertFailsWith<ByteReaderException> { JvmFrameValue.Reference(descriptor) }
        }
        assertFailsWith<ByteReaderException> { JvmFrameValue.UninitializedNew(-1, "LA;") }
        assertFailsWith<ByteReaderException> { JvmFrameValue.UninitializedNew(65535, "LA;") }
        assertFailsWith<ByteReaderException> { JvmFrameValue.UninitializedNew(0, "[I") }
        assertFailsWith<ByteReaderException> { JvmFrameValue.UninitializedThis("[I") }
    }

    private fun frame(values: List<JvmFrameValue>) = JvmFrame(0, 20).also { frame -> values.forEach(frame::push) }
    private data class Form(val operation: JvmStackOperation, val before: List<JvmFrameValue>, val after: List<JvmFrameValue>)
}
