package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Copy reference" fqn formatting from a token's definition NodeId (the code-area menu's one derived item). */
class CodeReferenceTest {

    @Test
    fun typeTokenYieldsTheClassFqn() {
        val token = CodeToken("Bar", TokenKind.TYPE, NodeId("cls:com.foo.Bar"))
        assertEquals("com.foo.Bar", referenceFqn(token))
    }

    @Test
    fun methodTokenAppendsMemberNameToOwnerClass() {
        val token = CodeToken("run", TokenKind.METHOD, NodeId("cls:com.foo.Bar"))
        assertEquals("com.foo.Bar.run", referenceFqn(token))
    }

    @Test
    fun fieldTokenAppendsMemberNameToOwnerClass() {
        val token = CodeToken("count", TokenKind.FIELD, NodeId("cls:com.foo.Bar"))
        assertEquals("com.foo.Bar.count", referenceFqn(token))
    }

    @Test
    fun memberNodeIdResolvesToOwnerAndMember() {
        // A future mbr: definition (owner encoded first) still yields owner.member.
        val token = CodeToken("run", TokenKind.METHOD, NodeId("mbr:com.foo.Bar#com.foo.Bar#M:run()V"))
        assertEquals("com.foo.Bar.run", referenceFqn(token))
    }

    @Test
    fun aTokenWithoutADefinitionHasNoReference() {
        assertNull(referenceFqn(CodeToken("x", TokenKind.PLAIN, definition = null)))
        assertNull(referenceFqn(CodeToken("Bar", TokenKind.TYPE, definition = null)))
    }
}
