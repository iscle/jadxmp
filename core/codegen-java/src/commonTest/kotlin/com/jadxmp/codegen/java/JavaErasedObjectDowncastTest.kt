package com.jadxmp.codegen.java

import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * DEX lets an `invoke-{interface,virtual}` dispatch on an erased `java.lang.Object` value with no
 * preceding `check-cast` (e.g. `Iterator.next()` used directly as a `Map.Entry` receiver —
 * types/TestGenerics2). Type inference recovers the real type from that receiver use, but the *Java*
 * expression (`it.next()`) is still statically `Object`, so codegen must insert an explicit `(T)` cast
 * when the call's declared return is the root `Object` yet the value's inferred type is a strictly
 * narrower reference — otherwise the assignment/use does not compile ("Object cannot be converted…").
 * The cast fires ONLY on that exact shape; every other call renders unchanged.
 */
class JavaErasedObjectDowncastTest {

    private val bar = IrType.objectType("a.Bar")
    private val list = IrType.objectType("java.util.List")

    private fun render(returnType: IrType, varType: IrType): String {
        val cls = irClass("a.Foo")
        cls.method("m", returnType = IrType.VOID) {
            val v = Local(0, varType)
            body(
                assign(v.ref(), staticInvoke(bar, "produce", returnType, emptyList(), emptyList())),
                ret(),
            )
        }
        return generate(cls)
    }

    @Test
    fun erasedObjectResultNarrowedToNamedTypeGetsDowncast() {
        // Declared return is Object; the value was narrowed to List — so the RHS needs `(List)`.
        assertThatCode(render(IrType.OBJECT, list))
            .containsOne("(List) Bar.produce()")
    }

    @Test
    fun objectResultLeftAsObjectHasNoCast() {
        // Not narrowed (stays Object) — no cast (a `(Object)` cast would be pointless and non-jadx).
        assertThatCode(render(IrType.OBJECT, IrType.OBJECT))
            .doesNotContain("(Object) Bar.produce()")
            .containsOne("Bar.produce()")
    }

    @Test
    fun namedReturnTypeIsNotDowncast() {
        // Declared return is already List — the guard fires only for the erased root Object return, so
        // no cast is emitted even into a List local.
        assertThatCode(render(list, list))
            .doesNotContain("(List) Bar.produce()")
            .containsOne("Bar.produce()")
    }
}
