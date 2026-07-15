package com.jadxmp.ui.client

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [commentReferenceKey] — the pure projection of a commented `CodeNodeRef` to the SAME
 * fully-qualified string `referenceFqn` builds for a code token, so the code area can label its context item
 * "Add comment…" vs "Edit comment…" by a synchronous membership test. A class projects to its fqn; a
 * method/field to `owner.member` (a method's overload arity is intentionally dropped, matching `referenceFqn`).
 */
class CommentReferenceKeyTest {

    @Test
    fun classProjectsToItsFullName() {
        assertEquals("com.example.Foo", commentReferenceKey(ClassNodeRef("com.example.Foo")))
    }

    @Test
    fun methodProjectsToOwnerDotName() {
        assertEquals(
            "com.example.Foo.doWork",
            commentReferenceKey(MethodNodeRef("com.example.Foo", "doWork", listOf("int", "java.lang.String"))),
        )
    }

    @Test
    fun fieldProjectsToOwnerDotName() {
        assertEquals("com.example.Foo.count", commentReferenceKey(FieldNodeRef("com.example.Foo", "count")))
    }
}
