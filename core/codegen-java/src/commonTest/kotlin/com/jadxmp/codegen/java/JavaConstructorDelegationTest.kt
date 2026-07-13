package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * Constructor delegation renders `super(...)` vs `this(...)` by comparing the invoke target's class to
 * the enclosing class (DEX emits both as `invoke-direct`, so the invoke *kind* cannot distinguish them).
 *
 * F4a guard: the comparison is on fully-qualified names, and an inner class carries a `$` in that name
 * on BOTH the class definition and the invoke target (both come from the same descriptor parser), so an
 * inner-class same-class delegation must render `this(...)`, never `super(...)`.
 */
class JavaConstructorDelegationTest {

    /** `invoke-direct <owner>.<init>(...)` — the un-normalized constructor-delegation form. */
    private fun initInvoke(owner: IrType, argTypes: List<IrType>, args: List<com.jadxmp.ir.insn.Operand>) =
        InvokeInstruction(MethodRef(owner, MethodRef.CONSTRUCTOR_NAME, owner, argTypes), InvokeKind.DIRECT, result = null, args = args)

    @Test
    fun superConstructorCallRendersAsSuper() {
        val self = IrType.objectType("a.Foo")
        val obj = IrType.OBJECT
        val cls = irClass("a.Foo", superType = obj)
        // A delegation's receiver is the object under construction — `this`.
        val thisRef = Local(0, self, isThis = true)
        cls.method("<init>") {
            thisArg = thisRef.ssaValue
            body(initInvoke(obj, emptyList(), listOf(thisRef.ref()))) // invoke-direct Object.<init>()
        }
        assertThatCode(generate(cls))
            .containsOne("super();")
            .doesNotContain("this(")
    }

    @Test
    fun innerClassSameClassDelegationRendersAsThis() {
        // Inner class fullName carries a '$'; the invoke target must match it for `this(...)`.
        val inner = IrType.objectType("a.Foo\$Inner")
        val cls = irClass("a.Foo\$Inner")
        val thisRef = Local(0, inner, isThis = true)
        cls.method("<init>", argTypes = listOf(IrType.INT)) {
            thisArg = thisRef.ssaValue
            this[CodegenKeys.PARAM_NAMES] = listOf("x")
            // invoke-direct a.Foo$Inner.<init>() — delegate to the no-arg constructor of the SAME class.
            body(initInvoke(inner, emptyList(), listOf(thisRef.ref())))
        }
        assertThatCode(generate(cls))
            .containsOne("this();")
            .doesNotContain("super(")
    }

    @Test
    fun initInvokeOnANewedObjectRendersAsNewNotSuper() {
        // A `<init>` on an object that is NOT `this` (a freshly-created one) is a constructor CALL, not a
        // delegation — it must render `new T(...)`, never an illegal `super(...)` mid-method.
        val cls = irClass("a.Foo")
        cls.method("make") {
            val obj = Local(1, IrType.objectType("java.io.FileNotFoundException"))
            body(
                assign(obj.ref(), com.jadxmp.ir.insn.TypeInstruction(com.jadxmp.ir.insn.IrOpcode.NEW_INSTANCE, IrType.objectType("java.io.FileNotFoundException"))),
                initInvoke(IrType.objectType("java.io.FileNotFoundException"), listOf(IrType.STRING), listOf(obj.ref(), expr(constString("x")))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("new FileNotFoundException(\"x\");")
            .doesNotContain("super(")
            .doesNotContain("this(")
    }
}
