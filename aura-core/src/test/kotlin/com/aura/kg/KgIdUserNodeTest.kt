package com.aura.kg

import org.junit.Test
import kotlin.test.assertEquals

class KgIdUserNodeTest {

    @Test
    fun `USER_NODE_ID matches a PERSON node labelled user`() {
        // The extractor is instructed to label the speaker "user"; promotion
        // identifies beliefs about the user by comparing an edge's sourceId
        // against this constant, so the two must agree exactly.
        assertEquals(KgId.node(NodeType.PERSON, "user"), KgId.USER_NODE_ID)
    }

    @Test
    fun `USER_NODE_ID is case and whitespace insensitive at the label`() {
        assertEquals(KgId.USER_NODE_ID, KgId.node(NodeType.PERSON, "  User "))
    }
}
